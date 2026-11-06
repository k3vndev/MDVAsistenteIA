package me.kev.sva.ai;

/** Token counters reported by the provider. */
public record TokenUsage(long input, long cached, long output) {
  public long total() {
    return input + output;
  }
}
