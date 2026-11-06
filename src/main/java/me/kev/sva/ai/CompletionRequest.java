package me.kev.sva.ai;

import java.util.List;

/**
 * Provider-neutral request for one model call.
 *
 * @param messages          messages in their final order
 * @param modelOverride     null/blank = the provider's model
 * @param maxOutputTokens   null = the provider's limit
 * @param temperature       null = the provider's temperature
 * @param timeoutMsOverride null = the provider's timeout; never below 3000 ms
 * @param jsonObject        request JSON mode (only applied when type=openai)
 * @param promptCacheKey    OpenAI prompt_cache_key (only sent when type=openai)
 * @param usageLogLabel     text right after "AI token usage: " (for example "scene=42")
 * @param usageLogSuffix    optional text appended at the end of the usage line
 */
public record CompletionRequest(
    List<CompletionMessage> messages,
    String modelOverride,
    Long maxOutputTokens,
    Double temperature,
    Long timeoutMsOverride,
    boolean jsonObject,
    String promptCacheKey,
    String usageLogLabel,
    String usageLogSuffix) {

  public static final long MIN_TIMEOUT_MS = 3_000L;

  public CompletionRequest {
    messages = List.copyOf(messages);
    usageLogLabel = usageLogLabel == null ? "" : usageLogLabel;
    usageLogSuffix = usageLogSuffix == null ? "" : usageLogSuffix;
  }

  /** Model actually used for this request on the given provider. */
  public String effectiveModel(ProviderSettings provider) {
    if (modelOverride != null && !modelOverride.isBlank()) return modelOverride.trim();
    return provider == null ? null : provider.model();
  }

  public long effectiveTimeoutMs(ProviderSettings provider) {
    long base = timeoutMsOverride != null ? timeoutMsOverride : provider.requestTimeoutMs();
    return Math.max(base, MIN_TIMEOUT_MS);
  }
}
