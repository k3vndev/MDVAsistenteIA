package me.kev.sva.api;

/**
 * Queue priority.
 *
 * <p>{@link #INTERACTIVE} is for a player waiting on the answer and is served first.
 * {@link #BACKGROUND} (the default) is for batch work such as generating content and
 * also waits whenever the provider is close to its per-minute limit, so Isolda keeps room.</p>
 */
public enum AiPriority {
  INTERACTIVE,
  BACKGROUND
}
