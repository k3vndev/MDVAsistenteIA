package me.kev.sva.ai.external;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.ai.AiGateway;
import me.kev.sva.ai.ProviderSlot;
import me.kev.sva.api.AiException;
import me.kev.sva.api.AiException.Reason;
import me.kev.sva.api.AiRequest;
import me.kev.sva.api.AiResult;
import me.kev.sva.api.AiService;

/**
 * The {@link AiService} registered in Bukkit's ServicesManager. Created once per
 * plugin enable, so references held by other plugins survive /sva reload; each
 * reload swaps in a new gateway and queue.
 */
public final class AiServiceImpl implements AiService {
  /** Client name used by ServerAssistant's own /sva api test command. */
  public static final String INTERNAL_CLIENT = "ServerAssistant";

  private final ServerAssistantPlugin plugin;
  /** Per-minute windows and daily budgets; survives reloads. */
  private final ApiQuotaTracker quota = new ApiQuotaTracker();
  private volatile AiGateway gateway;
  private volatile ApiSettings settings;
  private volatile ExternalRequestQueue queue;
  private volatile boolean shutdown = false;

  public AiServiceImpl(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  /** Main thread. Ends pending work with RELOADING and starts a fresh queue on the new gateway. */
  public void swapGateway(AiGateway newGateway, ApiSettings newSettings) {
    ExternalRequestQueue old = queue;
    if (old != null) old.cancelAll(Reason.RELOADING);
    this.gateway = newGateway;
    this.settings = newSettings;
    this.queue = new ExternalRequestQueue(plugin, newGateway, newSettings, quota);
    shutdown = false;
  }

  /** Main thread. Ends everything pending with {@code reason}; new requests fail with it too until the next swap. */
  public void cancelAll(Reason reason) {
    ExternalRequestQueue old = queue;
    if (old != null) old.cancelAll(reason);
    if (reason == Reason.SHUTDOWN) shutdown = true;
  }

  @Override
  public boolean isAvailable() {
    AiGateway currentGateway = gateway;
    ApiSettings currentSettings = settings;
    return !shutdown
        && plugin.isEnabled()
        && currentSettings != null
        && currentSettings.enabled()
        && currentGateway != null
        && !currentGateway.isShutdown()
        && (currentGateway.isAvailable(ProviderSlot.PRIMARY) || currentGateway.isAvailable(ProviderSlot.FALLBACK));
  }

  @Override
  public boolean isAllowed(Plugin caller) {
    ApiSettings currentSettings = settings;
    return caller != null && currentSettings != null && currentSettings.effectiveClient(caller.getName()) != null;
  }

  @Override
  public CompletableFuture<AiResult> request(Plugin caller, AiRequest request) {
    CompletableFuture<AiResult> future = new CompletableFuture<>();
    if (caller == null || request == null) {
      onMain(() -> future.completeExceptionally(new AiException(
          Reason.INVALID_REQUEST, "caller and request must not be null")), future);
      return future;
    }
    String clientName = caller.getName();
    onMain(() -> {
      ApiSettings currentSettings = settings;
      submit(clientName, currentSettings == null ? null : currentSettings.effectiveClient(clientName), request, future);
    }, future);
    return future;
  }

  /** Main thread. Request from ServerAssistant itself; always allowed, any profile, no client quota. */
  public CompletableFuture<AiResult> requestInternal(AiRequest request) {
    CompletableFuture<AiResult> future = new CompletableFuture<>();
    ApiSettings currentSettings = settings;
    ApiClientPolicy policy = new ApiClientPolicy(INTERNAL_CLIENT, true,
        currentSettings == null ? List.of() : currentSettings.profileNames(), 0, 0, 0L);
    submit(INTERNAL_CLIENT, policy, request, future);
    return future;
  }

  private void submit(String clientName, ApiClientPolicy policy, AiRequest request, CompletableFuture<AiResult> future) {
    ExternalRequestQueue current = queue;
    if (shutdown || current == null) {
      future.completeExceptionally(new AiException(Reason.SHUTDOWN, "ServerAssistant is not running."));
      return;
    }
    current.submit(clientName, policy, request, future);
  }

  private void onMain(Runnable task, CompletableFuture<AiResult> future) {
    if (Bukkit.isPrimaryThread()) {
      task.run();
      return;
    }
    if (shutdown || !plugin.isEnabled()) {
      // Cannot reach the main thread any more; complete here instead of never.
      future.completeExceptionally(new AiException(Reason.SHUTDOWN, "ServerAssistant is not running."));
      return;
    }
    try {
      plugin.getServer().getScheduler().runTask(plugin, task);
    } catch (RuntimeException ex) {
      future.completeExceptionally(new AiException(Reason.SHUTDOWN, "ServerAssistant is not running.", ex));
    }
  }

  // ---- status for /sva api status ----

  public ApiSettings settings() {
    return settings;
  }

  public AiGateway gateway() {
    return gateway;
  }

  public ApiQuotaTracker quota() {
    return quota;
  }

  public int interactiveQueued() {
    ExternalRequestQueue current = queue;
    return current == null ? 0 : current.interactiveSize();
  }

  public int backgroundQueued() {
    ExternalRequestQueue current = queue;
    return current == null ? 0 : current.backgroundSize();
  }

  public int inFlight() {
    ExternalRequestQueue current = queue;
    return current == null ? 0 : current.inFlightCount();
  }
}
