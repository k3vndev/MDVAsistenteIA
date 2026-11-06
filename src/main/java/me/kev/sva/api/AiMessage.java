package me.kev.sva.api;

import java.util.Objects;

/** One message of an {@link AiRequest}. Content must not be blank. */
public record AiMessage(AiRole role, String content) {
  public AiMessage {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
  }
}
