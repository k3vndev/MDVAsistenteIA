package me.kev.sva.ai.external;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.bukkit.scheduler.BukkitTask;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.ai.AiGateway;
import me.kev.sva.ai.CompletionMessage;
import me.kev.sva.ai.CompletionRequest;
import me.kev.sva.ai.CompletionResult;
import me.kev.sva.ai.ProviderErrors;
import me.kev.sva.ai.ProviderSettings;
import me.kev.sva.ai.ProviderSlot;
import me.kev.sva.ai.TokenUsage;
import me.kev.sva.api.AiException;
import me.kev.sva.api.AiException.Reason;
import me.kev.sva.api.AiMessage;
import me.kev.sva.api.AiPriority;
import me.kev.sva.api.AiRequest;
import me.kev.sva.api.AiResponseFormat;
import me.kev.sva.api.AiResult;

/**
 * Queue for external API requests: admission, priority, concurrency, Isolda's
 * reserve, one fallback hop and logging. One instance per gateway; /sva reload
 * cancels it and creates a new one.
 *
 * <p>Main thread only. Every future completes on the main thread, exactly once.</p>
 */
final class ExternalRequestQueue {
  private final ServerAssistantPlugin plugin;
  private final AiGateway gateway;
  private final ApiSettings settings;
  private final ApiQuotaTracker quota;

  private final Deque<Job> interactive = new ArrayDeque<>();
  private final Deque<Job> background = new ArrayDeque<>();
  private final Set<Job> inFlight = new LinkedHashSet<>();
  private BukkitTask pumpTask;
  private boolean closed = false;
  private boolean pumping = false;
  private boolean repump = false;

  ExternalRequestQueue(ServerAssistantPlugin plugin, AiGateway gateway, ApiSettings settings, ApiQuotaTracker quota) {
    this.plugin = plugin;
    this.gateway = gateway;
    this.settings = settings;
    this.quota = quota;
  }

  ApiSettings settings() {
    return settings;
  }

  int interactiveSize() {
    return interactive.size();
  }

  int backgroundSize() {
    return background.size();
  }

  int inFlightCount() {
    return inFlight.size();
  }

  /**
   * Runs admission and queues the request.
   *
   * @param policy null if the client is not allowed
   */
  void submit(String clientName, ApiClientPolicy policy, AiRequest request, CompletableFuture<AiResult> future) {
    if (closed) {
      fail(future, clientName, request, Reason.RELOADING, "API queue is closed.", null);
      return;
    }
    if (!settings.enabled()) {
      fail(future, clientName, request, Reason.DISABLED, "api.enabled is false.", null);
      return;
    }
    if (gateway == null || (!gateway.isAvailable(ProviderSlot.PRIMARY) && !gateway.isAvailable(ProviderSlot.FALLBACK))) {
      fail(future, clientName, request, Reason.NOT_CONFIGURED, "No AI provider is configured.", null);
      return;
    }
    if (policy == null) {
      fail(future, clientName, request, Reason.CLIENT_NOT_ALLOWED,
          clientName + " is not allowed under api.clients.", null);
      return;
    }
    ApiProfile profile = settings.profile(request.profile());
    if (profile == null || !policy.allowsProfile(request.profile())) {
      fail(future, clientName, request, Reason.PROFILE_NOT_ALLOWED,
          "Profile '" + request.profile() + "' is not allowed for " + clientName + ".", null);
      return;
    }
    if (request.messages().size() > settings.maxMessages()) {
      fail(future, clientName, request, Reason.INVALID_REQUEST,
          "Too many messages (max " + settings.maxMessages() + ").", null);
      return;
    }
    if (request.inputChars() > settings.maxInputChars()) {
      fail(future, clientName, request, Reason.INVALID_REQUEST,
          "Input too long (max " + settings.maxInputChars() + " characters).", null);
      return;
    }
    Reason quotaReason = quota.checkAdmission(policy, request.playerId(), settings.maxRequestsPerMinute());
    if (quotaReason != null) {
      fail(future, clientName, request, quotaReason, "Limit reached for " + clientName + ".", null);
      return;
    }
    if (interactive.size() + background.size() >= settings.maxQueueSize()) {
      fail(future, clientName, request, Reason.QUEUE_FULL, "Too many requests waiting.", null);
      return;
    }

    quota.recordStart(policy.name(), request.playerId());
    Job job = new Job(clientName, profile, request, future, System.currentTimeMillis());
    (request.priority() == AiPriority.INTERACTIVE ? interactive : background).addLast(job);
    pump();
  }

  /** Fails everything queued or in flight. Late gateway callbacks are ignored afterwards. */
  void cancelAll(Reason reason) {
    closed = true;
    cancelPumpTask();
    List<Job> all = new ArrayList<>(inFlight);
    all.addAll(interactive);
    all.addAll(background);
    inFlight.clear();
    interactive.clear();
    background.clear();
    for (Job job : all) {
      fail(job, reason, "Request cancelled (" + reason.name().toLowerCase(Locale.ROOT) + ").", null);
    }
  }

  private void pump() {
    if (pumping) {
      repump = true;
      return;
    }
    pumping = true;
    try {
      do {
        repump = false;
        pumpOnce();
      } while (repump && !closed);
    } finally {
      pumping = false;
    }
  }

  private void pumpOnce() {
    cancelPumpTask();
    if (closed) return;
    long now = System.currentTimeMillis();
    expire(interactive, now);
    expire(background, now);

    long nextCheckMs = -1L;
    while (inFlight.size() < settings.maxConcurrentRequests()) {
      Deque<Job> deque = !interactive.isEmpty() ? interactive : background;
      Job job = deque.peekFirst();
      if (job == null) break;

      DispatchPolicy.Decision decision = DispatchPolicy.decide(input(job, now));
      if (decision.kind() == DispatchPolicy.Kind.GO) {
        deque.removeFirst();
        start(job, decision.slot());
      } else if (decision.kind() == DispatchPolicy.Kind.FAIL) {
        deque.removeFirst();
        fail(job, decision.reason(), "Could not dispatch the request.", null);
      } else {
        nextCheckMs = decision.waitMs();
        break;
      }
    }

    // Wake up again for a provider delay or, if anything is still queued, for its queue deadline.
    long deadline = nextDeadlineMs(now);
    if (deadline >= 0 && (nextCheckMs < 0 || deadline < nextCheckMs)) nextCheckMs = deadline;
    if (nextCheckMs >= 0 && plugin.isEnabled()) {
      long ticks = Math.max(1L, (nextCheckMs + 49L) / 50L);
      pumpTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
        pumpTask = null;
        pump();
      }, ticks);
    }
  }

  private void expire(Deque<Job> deque, long now) {
    long max = settings.maxQueueWaitMs();
    if (max <= 0) return;
    // Remove first, complete afterwards: a caller's callback may queue a new request.
    List<Job> expired = new ArrayList<>();
    deque.removeIf(job -> {
      if (now - job.enqueuedAt < max) return false;
      expired.add(job);
      return true;
    });
    for (Job job : expired) {
      fail(job, Reason.QUEUE_TIMEOUT, "Waited more than " + max + "ms in the queue.", null);
    }
  }

  private long nextDeadlineMs(long now) {
    long max = settings.maxQueueWaitMs();
    if (max <= 0) return -1L;
    long earliest = Long.MAX_VALUE;
    for (Job job : interactive) earliest = Math.min(earliest, job.enqueuedAt + max);
    for (Job job : background) earliest = Math.min(earliest, job.enqueuedAt + max);
    return earliest == Long.MAX_VALUE ? -1L : Math.max(0L, earliest - now);
  }

  private DispatchPolicy.Input input(Job job, long now) {
    ProviderSlot preferred = job.forcedSlot != null ? job.forcedSlot : job.profile.slot();
    ProviderSlot alternate = other(preferred);
    boolean mayAlternate = job.forcedSlot == null && job.profile.allowFallback();
    return new DispatchPolicy.Input(
        job.request.priority(),
        slotState(job, preferred),
        mayAlternate ? slotState(job, alternate) : null,
        settings.reserveForAssistantRpm(),
        maxFallbackWaitMs(),
        now - job.enqueuedAt,
        settings.maxQueueWaitMs());
  }

  private DispatchPolicy.SlotState slotState(Job job, ProviderSlot slot) {
    ProviderSettings provider = gateway.settings(slot);
    String model = modelFor(job, slot);
    return new DispatchPolicy.SlotState(
        slot,
        gateway.isAvailable(slot),
        gateway.delayMs(slot, model),
        gateway.requestsLastMinute(slot, model),
        provider == null ? 0 : provider.maxRequestsPerMinute());
  }

  /** The profile's model override only applies to the profile's own provider. */
  private static String modelFor(Job job, ProviderSlot slot) {
    return slot == job.profile.slot() ? job.profile.model() : null;
  }

  private static ProviderSlot other(ProviderSlot slot) {
    return slot == ProviderSlot.PRIMARY ? ProviderSlot.FALLBACK : ProviderSlot.PRIMARY;
  }

  private long maxFallbackWaitMs() {
    return Math.max(plugin.getConfig().getLong("ai.fallback.max-wait-ms", 2500L), 0L);
  }

  private void start(Job job, ProviderSlot slot) {
    ProviderSettings provider = gateway.settings(slot);
    job.slot = slot;
    inFlight.add(job);

    List<CompletionMessage> messages = new ArrayList<>();
    if (!settings.baseSystemPrompt().isBlank()) {
      messages.add(CompletionMessage.system(settings.baseSystemPrompt()));
    }
    for (AiMessage message : job.request.messages()) {
      messages.add(switch (message.role()) {
        case SYSTEM -> CompletionMessage.system(message.content());
        case USER -> CompletionMessage.user(message.content());
        case ASSISTANT -> CompletionMessage.assistant(message.content());
      });
    }

    Long limit = job.profile.maxOutputTokens() != null
        ? job.profile.maxOutputTokens()
        : provider == null ? null : Long.valueOf(provider.maxOutputTokens());
    Long maxOutput = job.request.maxOutputTokens() == null
        ? job.profile.maxOutputTokens()
        : limit == null ? Long.valueOf(job.request.maxOutputTokens())
        : Long.valueOf(Math.min(job.request.maxOutputTokens(), limit));
    Double temperature = job.request.temperature() != null ? job.request.temperature() : job.profile.temperature();

    CompletionRequest completion = new CompletionRequest(
        messages,
        modelFor(job, slot),
        maxOutput,
        temperature,
        job.profile.timeoutMs(),
        job.request.format() == AiResponseFormat.JSON_OBJECT,
        null, // never share Isolda's prompt_cache_key
        "client=" + job.client + " profile=" + job.profile.name() + " tag=" + job.request.tag(),
        "");

    gateway.complete(slot, completion, (result, error) -> onComplete(job, result, error));
  }

  private void onComplete(Job job, CompletionResult result, Throwable error) {
    if (closed || !inFlight.remove(job)) return; // already failed by cancelAll
    try {
      handle(job, result, error);
    } finally {
      pump();
    }
  }

  private void handle(Job job, CompletionResult result, Throwable error) {
    if (error == null && result != null) {
      TokenUsage usage = result.usage();
      long input = usage == null ? 0L : usage.input();
      long output = usage == null ? 0L : usage.output();
      quota.recordUsage(job.client, input + output);
      AiResult aiResult = new AiResult(
          result.text(), result.providerName(), result.model(),
          clampInt(input), clampInt(usage == null ? 0L : usage.cached()), clampInt(output),
          result.latencyMs(), job.slot != job.profile.slot());
      log(job, result.providerName(), result.model(), input, output, result.latencyMs(), "ok");
      job.future.complete(aiResult);
      return;
    }

    Throwable cause = error != null ? error : new IllegalStateException("Empty provider result.");
    String model = modelFor(job, job.slot);
    if (ProviderErrors.isRateLimit(cause)) {
      gateway.applyCooldown(job.slot, model, ProviderErrors.parseRetryDelay(cause, 60_000L));
      if (retryOnOther(job)) return;
      fail(job, Reason.RATE_LIMITED, "Provider rate limit reached.", cause);
    } else if (cause instanceof TimeoutException) {
      fail(job, Reason.TIMEOUT, cause.getMessage(), cause);
    } else if (ProviderErrors.isTransient(cause)) {
      if (retryOnOther(job)) return;
      fail(job, isTimeout(cause) ? Reason.TIMEOUT : Reason.PROVIDER_ERROR, "Temporary provider error.", cause);
    } else {
      fail(job, Reason.PROVIDER_ERROR, cause.getClass().getSimpleName(), cause);
    }
  }

  private static boolean isTimeout(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof TimeoutException || t instanceof java.io.InterruptedIOException) return true;
      String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("timed out") || message.contains("timeout")) return true;
    }
    return false;
  }

  /** One hop to the other provider, never a retry on the same one. */
  private boolean retryOnOther(Job job) {
    if (job.forcedSlot != null || !job.profile.allowFallback()) return false;
    ProviderSlot alternate = other(job.slot);
    if (!gateway.isAvailable(alternate)) return false;
    if (gateway.delayMs(alternate, modelFor(job, alternate)) > maxFallbackWaitMs()) return false;
    job.forcedSlot = alternate;
    job.enqueuedAt = System.currentTimeMillis();
    (job.request.priority() == AiPriority.INTERACTIVE ? interactive : background).addFirst(job);
    return true;
  }

  private void fail(Job job, Reason reason, String message, Throwable cause) {
    ProviderSettings provider = job.slot == null ? null : gateway.settings(job.slot);
    String model = provider == null ? "-"
        : modelFor(job, job.slot) != null ? modelFor(job, job.slot) : provider.model();
    log(job, provider == null ? "-" : provider.displayName(), model, 0L, 0L, 0L, reason.name());
    job.future.completeExceptionally(new AiException(reason, message, cause));
  }

  private void fail(
      CompletableFuture<AiResult> future, String client, AiRequest request,
      Reason reason, String message, Throwable cause) {
    if (settings.logRequests()) {
      plugin.getLogger().info("AI API: client=" + client
          + " profile=" + (request == null ? "-" : request.profile())
          + " tag=" + (request == null ? "-" : request.tag())
          + " provider=- model=- in=0 out=0 ms=0 result=" + reason.name());
    }
    future.completeExceptionally(new AiException(reason, message, cause));
  }

  /** Never logs prompt content. */
  private void log(Job job, String provider, String model, long in, long out, long ms, String result) {
    if (!settings.logRequests()) return;
    plugin.getLogger().info("AI API: client=" + job.client
        + " profile=" + job.profile.name()
        + " tag=" + job.request.tag()
        + " provider=" + provider
        + " model=" + model
        + " in=" + in
        + " out=" + out
        + " ms=" + ms
        + " result=" + result);
  }

  private void cancelPumpTask() {
    if (pumpTask != null) {
      pumpTask.cancel();
      pumpTask = null;
    }
  }

  private static int clampInt(long value) {
    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
  }

  private static final class Job {
    final String client;
    final ApiProfile profile;
    final AiRequest request;
    final CompletableFuture<AiResult> future;
    long enqueuedAt;
    /** Set for the single fallback hop after an error. */
    ProviderSlot forcedSlot;
    /** Slot of the current/last attempt. */
    ProviderSlot slot;

    Job(String client, ApiProfile profile, AiRequest request, CompletableFuture<AiResult> future, long enqueuedAt) {
      this.client = client;
      this.profile = profile;
      this.request = request;
      this.future = future;
      this.enqueuedAt = enqueuedAt;
    }
  }
}
