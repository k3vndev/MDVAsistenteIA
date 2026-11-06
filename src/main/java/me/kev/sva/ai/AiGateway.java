package me.kev.sva.ai;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.bukkit.scheduler.BukkitTask;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.utils.MessageSender;

/**
 * Model access shared by Isolda and the external AI API: provider clients, the
 * off-thread executor, the watchdog and token logging.
 *
 * <p>It knows nothing about Isolda's prompt or JSON protocol. One instance lives
 * per plugin load; /sva reload shuts it down and creates a new one. The throttle
 * registry is owned by the plugin, so cooldowns survive the reload.</p>
 *
 * <p>All public methods are called from the Bukkit main thread.</p>
 */
public final class AiGateway {
  private static final long WATCHDOG_GRACE_MS = 5_000L;

  private final ServerAssistantPlugin plugin;
  private final ProviderThrottleRegistry throttle;
  private final ProviderRuntime primary;
  private final ProviderRuntime fallback;
  private final ExecutorService requestExecutor;
  private volatile boolean shutdown = false;

  public AiGateway(ServerAssistantPlugin plugin, ProviderThrottleRegistry throttle) {
    this.plugin = plugin;
    this.throttle = throttle;
    this.primary = createRuntime(ProviderSettings.primary(plugin), "primary");

    ProviderSettings fallbackSettings = ProviderSettings.fallback(plugin);
    this.fallback = fallbackSettings == null ? null : createRuntime(fallbackSettings, "fallback");

    logRuntime(primary, "Primary");
    if (fallback != null) {
      logRuntime(fallback, "Fallback");
    } else {
      plugin.getLogger().info("AI fallback: disabled");
    }

    AtomicInteger counter = new AtomicInteger();
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "ServerAssistant-AI-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    // Callers serialize or cap their own requests. A cached pool only guarantees that
    // a call abandoned by the watchdog can never block the thread the next one needs.
    requestExecutor = Executors.newCachedThreadPool(factory);
  }

  private ProviderRuntime createRuntime(ProviderSettings settings, String role) {
    if (settings == null || !settings.isConfigured()) {
      if (settings != null) {
        MessageSender.Error(role + " " + settings.displayName() + " API key/provider not configured.");
      }
      return new ProviderRuntime(settings, null);
    }

    // The SDK defaults (10 min timeout, 2 hidden retries) let one stuck provider call
    // silence Isolda for minutes and made the local RPM cap count 1 call for up to 3.
    // The callers already own fallback/retry, so the SDK must fail fast.
    OpenAIClient client = OpenAIOkHttpClient.builder()
        .apiKey(settings.resolveApiKey())
        .baseUrl(settings.baseUrl())
        .timeout(Duration.ofMillis(settings.requestTimeoutMs()))
        .maxRetries(0)
        .build();
    return new ProviderRuntime(settings, client);
  }

  private void logRuntime(ProviderRuntime runtime, String role) {
    if (runtime == null || runtime.settings() == null) {
      return;
    }
    ProviderSettings settings = runtime.settings();
    plugin.getLogger().info(role + " AI provider: " + settings.displayName());
    plugin.getLogger().info(role + " AI model: " + settings.model());
    plugin.getLogger().info(role + " AI max output tokens: " + settings.maxOutputTokens());
    plugin.getLogger().info(role + " AI request timeout: " + settings.requestTimeoutMs() + "ms");
    plugin.getLogger().info(role + " AI local RPM cap: "
        + (settings.maxRequestsPerMinute() == 0 ? "unlimited" : settings.maxRequestsPerMinute()));
  }

  private ProviderRuntime runtime(ProviderSlot slot) {
    return slot == ProviderSlot.FALLBACK ? fallback : primary;
  }

  public boolean isAvailable(ProviderSlot slot) {
    ProviderRuntime runtime = runtime(slot);
    return runtime != null && runtime.client() != null && runtime.settings() != null;
  }

  public boolean hasFallback() {
    return isAvailable(ProviderSlot.FALLBACK);
  }

  public boolean isShutdown() {
    return shutdown;
  }

  public ProviderSettings settings(ProviderSlot slot) {
    ProviderRuntime runtime = runtime(slot);
    return runtime == null ? null : runtime.settings();
  }

  public ProviderThrottleRegistry throttle() {
    return throttle;
  }

  /** Delay before this slot may be called again (local RPM window + provider cooldown). */
  public long delayMs(ProviderSlot slot, String modelOverride) {
    ProviderSettings settings = settings(slot);
    if (settings == null) return 0L;
    return throttle.getDelay(settings.throttleKey(modelOverride), settings.maxRequestsPerMinute());
  }

  public void applyCooldown(ProviderSlot slot, String modelOverride, long ms) {
    ProviderSettings settings = settings(slot);
    if (settings == null) return;
    throttle.applyCooldown(settings.throttleKey(modelOverride), ms);
  }

  /** Requests counted in the last minute on this slot's bucket for the effective model. */
  public int requestsLastMinute(ProviderSlot slot, String modelOverride) {
    ProviderSettings settings = settings(slot);
    if (settings == null) return 0;
    return throttle.requestsLastMinute(settings.throttleKey(modelOverride));
  }

  /**
   * Records one attempt on the slot's throttle bucket, runs the call off-thread and
   * completes EXACTLY ONCE on the main thread (call result or watchdog timeout).
   *
   * <p>After {@link #shutdown()} a late call result is dropped; only the watchdog
   * still reports, exactly as before the gateway existed.</p>
   */
  public void complete(
      ProviderSlot slot,
      CompletionRequest request,
      BiConsumer<CompletionResult, Throwable> completion) {

    if (shutdown) {
      completion.accept(null, new IllegalStateException("AI gateway is shut down."));
      return;
    }

    ProviderRuntime runtime = runtime(slot);
    if (runtime == null || runtime.settings() == null) {
      completion.accept(null, new IllegalStateException("Selected AI provider is not configured."));
      return;
    }

    ProviderSettings provider = runtime.settings();
    String model = request.effectiveModel(provider);
    // Every call, Isolda's or external, counts in the same per-model bucket.
    throttle.recordAttempt(provider.throttleKey(request.modelOverride()));

    if (model == null || model.isBlank()) {
      completion.accept(null, new IllegalStateException("AI model is not configured."));
      return;
    }

    if (runtime.client() == null) {
      completion.accept(null, new IllegalStateException(provider.displayName() + " API client is not configured."));
      return;
    }

    boolean openai = "openai".equalsIgnoreCase(provider.type());
    ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams
        .builder()
        .model(model)
        .maxCompletionTokens(request.maxOutputTokens() != null
            ? request.maxOutputTokens() : provider.maxOutputTokens())
        .temperature(request.temperature() != null
            ? request.temperature() : provider.temperature());

    // OpenAI prompt caching itself is automatic. prompt_cache_key only helps route
    // requests that share the same long prefix toward the same cache. Do not send
    // this OpenAI-specific field to Gemini/other OpenAI-compatible providers.
    String promptCacheKey = openai ? request.promptCacheKey() : null;
    if (promptCacheKey != null) {
      paramsBuilder.promptCacheKey(promptCacheKey);
    }

    // OpenAI JSON mode guarantees a syntactically valid JSON object. Keep
    // OpenAI-compatible fallback providers untouched unless explicitly supported.
    if (openai && request.jsonObject()) {
      paramsBuilder.responseFormat(ResponseFormatJsonObject.builder().build());
    }

    for (CompletionMessage message : request.messages()) {
      switch (message.role()) {
        case SYSTEM -> paramsBuilder.addSystemMessage(message.content());
        case USER -> paramsBuilder.addUserMessage(message.content());
        case ASSISTANT -> paramsBuilder.addAssistantMessage(message.content());
      }
    }

    ChatCompletionCreateParams params = paramsBuilder.build();
    long timeoutMs = request.timeoutMsOverride() == null
        ? provider.requestTimeoutMs()
        : request.effectiveTimeoutMs(provider);
    RequestOptions options = request.timeoutMsOverride() == null
        ? null
        : RequestOptions.builder().timeout(Duration.ofMillis(timeoutMs)).build();

    // The completion must run exactly once, or the caller keeps its in-flight slot
    // forever. Whichever finishes first wins: the provider call or the watchdog below.
    AtomicBoolean completed = new AtomicBoolean(false);
    AtomicReference<BukkitTask> watchdog = new AtomicReference<>();
    BiConsumer<CompletionResult, Throwable> completeOnce = (result, error) -> {
      if (!completed.compareAndSet(false, true)) return;
      BukkitTask task = watchdog.getAndSet(null);
      if (task != null) task.cancel();
      completion.accept(result, error);
    };

    long startedAt = System.currentTimeMillis();
    Future<?> call = requestExecutor.submit(() -> {
      CompletionResult result = null;
      Throwable failure = null;

      try {
        if (!shutdown) {
          ChatCompletion response = options == null
              ? runtime.client().chat().completions().create(params)
              : runtime.client().chat().completions().create(params, options);
          TokenUsage usage = usageOf(response);
          logTokenUsage(provider, model, request, usage, response != null);
          logPromptCacheUsage(provider, promptCacheKey, usage);
          String text = "";
          if (response != null && !response.choices().isEmpty()) {
            text = response.choices()
                .get(0)
                .message()
                .content()
                .orElse("");
          }
          result = new CompletionResult(text, slot, provider.displayName(), model, usage,
              System.currentTimeMillis() - startedAt);
        }
      } catch (Throwable error) {
        failure = error;
      }

      CompletionResult finalResult = result;
      Throwable finalFailure = failure;

      if (!shutdown && plugin.isEnabled()) {
        plugin.getServer().getScheduler().runTask(plugin, () -> completeOnce.accept(finalResult, finalFailure));
      }
    });

    // Safety net beyond the HTTP timeout (DNS hangs, a stuck stream, etc.). The grace
    // period lets the normal timeout report first with its real error message.
    long watchdogTicks = Math.max(1L, (timeoutMs + WATCHDOG_GRACE_MS + 49L) / 50L);
    watchdog.set(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      if (completed.get()) return;
      call.cancel(true);
      plugin.getLogger().warning(provider.displayName() + " request exceeded "
          + timeoutMs + "ms; releasing the AI queue.");
      completeOnce.accept(null, new TimeoutException(
          provider.displayName() + " request timed out after " + timeoutMs + "ms"));
    }, watchdogTicks));
  }

  private static TokenUsage usageOf(ChatCompletion response) {
    if (response == null) return null;
    return response.usage().map(usage -> new TokenUsage(
        usage.promptTokens(),
        usage.promptTokensDetails().flatMap(details -> details.cachedTokens()).orElse(0L),
        usage.completionTokens())).orElse(null);
  }

  /**
   * Per-request token diagnostics returned by the provider.
   *
   * <p>This is intentionally independent from prompt caching: when enabled, every
   * successful request that returns a usage object is logged. Chat Completions names
   * these counters prompt/completion tokens; the log uses input/output terminology
   * because it is easier to compare with the OpenAI Usage dashboard.</p>
   */
  private void logTokenUsage(
      ProviderSettings provider,
      String model,
      CompletionRequest request,
      TokenUsage usage,
      boolean hasResponse) {
    if (!plugin.getConfig().getBoolean("ai.usage-debug.enabled", false) || !hasResponse) {
      return;
    }
    plugin.getLogger().info(formatUsageLine(
        provider == null ? "unknown" : provider.displayName(),
        model == null ? "unknown" : model,
        request.usageLogLabel(),
        request.usageLogSuffix(),
        usage));
  }

  /** Builds the "AI token usage: ..." line. Package-visible for tests. */
  static String formatUsageLine(
      String providerName, String model, String label, String suffix, TokenUsage usage) {
    StringBuilder line = new StringBuilder("AI token usage: ").append(label)
        .append(" provider=").append(providerName)
        .append(" model=").append(model);
    if (usage == null) {
      return line.append(" usage=unavailable").toString();
    }
    line.append(" input=").append(usage.input())
        .append(" cached=").append(usage.cached())
        .append(" output=").append(usage.output())
        .append(" total=").append(usage.total());
    if (suffix != null && !suffix.isBlank()) {
      line.append(' ').append(suffix);
    }
    return line.toString();
  }

  /** Collapses a value to one short line for usage logs. */
  public static String oneLineForUsageLog(String value) {
    if (value == null || value.isBlank()) return "";
    String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
    if (oneLine.length() > 160) {
      return oneLine.substring(0, 157) + "...";
    }
    return oneLine;
  }

  private void logPromptCacheUsage(ProviderSettings provider, String promptCacheKey, TokenUsage usage) {
    // The per-request debug already includes cached tokens, so avoid duplicate
    // console lines if both the legacy and new diagnostics are enabled.
    if (promptCacheKey == null
        || usage == null
        || plugin.getConfig().getBoolean("ai.usage-debug.enabled", false)
        || !"openai".equalsIgnoreCase(provider.type())
        || !plugin.getConfig().getBoolean("ai.prompt-cache.log-usage", false)) {
      return;
    }
    plugin.getLogger().info("OpenAI prompt cache usage: prompt=" + usage.input()
        + ", cached=" + usage.cached()
        + ", completion=" + usage.output()
        + ", key=" + promptCacheKey);
  }

  public void shutdown() {
    shutdown = true;
    requestExecutor.shutdownNow();
    closeRuntime(primary);
    closeRuntime(fallback);
  }

  private void closeRuntime(ProviderRuntime runtime) {
    if (runtime == null || runtime.client() == null) {
      return;
    }
    try {
      runtime.client().close();
    } catch (Exception ignored) {
    }
  }
}
