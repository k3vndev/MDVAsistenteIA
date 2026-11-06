package me.kev.sva.api;

import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Answer of one model call.
 *
 * @param text         raw answer text (may be empty)
 * @param provider     provider display name, e.g. "OpenAI"
 * @param model        model that answered
 * @param latencyMs    time spent in the provider call
 * @param usedFallback true if the call went to the other provider than the profile's
 */
public record AiResult(
    String text,
    String provider,
    String model,
    int inputTokens,
    int cachedTokens,
    int outputTokens,
    long latencyMs,
    boolean usedFallback) {

  public AiResult {
    text = text == null ? "" : text;
  }

  /** Parses text as a JSON object, tolerating ```json fences and text around the first {...} block. */
  public Optional<JsonObject> json() {
    String block = firstJsonObject(text);
    if (block == null) return Optional.empty();
    try {
      JsonElement element = JsonParser.parseString(block);
      return element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  /** The first balanced {...} block, ignoring braces inside JSON strings. */
  private static String firstJsonObject(String value) {
    int start = value.indexOf('{');
    if (start < 0) return null;
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (inString) {
        if (escaped) escaped = false;
        else if (ch == '\\') escaped = true;
        else if (ch == '"') inString = false;
        continue;
      }
      if (ch == '"') inString = true;
      else if (ch == '{') depth++;
      else if (ch == '}' && --depth == 0) return value.substring(start, i + 1);
    }
    return null;
  }
}
