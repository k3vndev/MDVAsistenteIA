package me.kev.sva.api;

/**
 * Expected shape of the answer.
 *
 * <p>{@link #JSON_OBJECT} turns on the provider's JSON mode when it supports it
 * (OpenAI). Always ask for JSON in your instructions too, and read the answer with
 * {@link AiResult#json()}, which tolerates fences and surrounding text.</p>
 */
public enum AiResponseFormat {
  TEXT,
  JSON_OBJECT
}
