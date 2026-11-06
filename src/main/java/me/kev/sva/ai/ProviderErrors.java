package me.kev.sva.ai;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies provider failures. Shared by Isolda's queue and the external API queue. */
public final class ProviderErrors {
  private static final Pattern RETRY_DELAY = Pattern.compile(
      "(?:retry|try again)(?:\\s+after|\\s+in)?\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(?:s|sec|seconds?)",
      Pattern.CASE_INSENSITIVE);

  private ProviderErrors() {
  }

  public static boolean isRateLimit(Throwable error) {
    String type = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    return type.contains("ratelimit") || message.contains("429")
        || message.contains("resource_exhausted") || message.contains("rate limit");
  }

  public static boolean isTransient(Throwable error) {
    String type = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    return type.contains("internalserver") || type.contains("serviceunavailable") || type.contains("timeout")
        || message.contains("500") || message.contains("502") || message.contains("503") || message.contains("504")
        || message.contains("temporarily unavailable") || message.contains("timed out") || message.contains("connection reset");
  }

  public static long parseRetryDelay(Throwable error, long fallbackMs) {
    String message = String.valueOf(error.getMessage());
    Matcher matcher = RETRY_DELAY.matcher(message);
    if (matcher.find()) {
      try {
        return Math.max(1000L, (long) Math.ceil(Double.parseDouble(matcher.group(1)) * 1000D) + 250L);
      } catch (NumberFormatException ignored) {
      }
    }
    return Math.max(1000L, fallbackMs);
  }
}
