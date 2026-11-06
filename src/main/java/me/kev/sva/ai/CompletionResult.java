package me.kev.sva.ai;

/**
 * Raw text returned by one model call.
 *
 * @param usage null when the provider returned no usage object
 */
public record CompletionResult(
    String text,
    ProviderSlot slot,
    String providerName,
    String model,
    TokenUsage usage,
    long latencyMs) {

  public CompletionResult {
    text = text == null ? "" : text;
  }
}
