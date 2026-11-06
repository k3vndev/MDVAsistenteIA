package me.kev.sva.api;

import java.util.concurrent.CompletableFuture;

import org.bukkit.plugin.Plugin;

/**
 * One-off model calls for other plugins, registered in Bukkit's ServicesManager.
 *
 * <pre>{@code
 * AiService ai = Bukkit.getServicesManager().load(AiService.class);
 * }</pre>
 *
 * <p>Calls reuse ServerAssistant's configured providers but never Isolda's prompt,
 * memory or prompt cache key. The caller must be listed under {@code api.clients}
 * in ServerAssistant's config.yml (unless {@code api.unknown-clients: allow}).</p>
 *
 * <p>The reference stays valid across {@code /sva reload}; requests in flight
 * during a reload fail with {@link AiException.Reason#RELOADING}.</p>
 */
public interface AiService {

  /** True if the plugin is enabled, api.enabled is true and the primary or fallback provider is configured. */
  boolean isAvailable();

  /** Whether this caller plugin may use the API at all. */
  boolean isAllowed(Plugin caller);

  /**
   * Queues one model call with the caller's own instructions.
   *
   * <p>May be called from any thread; the returned future ALWAYS completes on the
   * Bukkit main thread. Never call {@code join()}/{@code get()} on the main thread.
   * Failures complete the future exceptionally with an {@link AiException}
   * (see {@link AiException.Reason}) instead of throwing synchronously.</p>
   */
  CompletableFuture<AiResult> request(Plugin caller, AiRequest request);
}
