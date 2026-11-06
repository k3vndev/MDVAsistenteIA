package me.kev.sva.ai;

import java.util.Objects;

/** One provider-neutral chat message, already in its final position in the prompt. */
public record CompletionMessage(Role role, String content) {
  public enum Role { SYSTEM, USER, ASSISTANT }

  public CompletionMessage {
    Objects.requireNonNull(role, "role");
    content = content == null ? "" : content;
  }

  public static CompletionMessage system(String content) {
    return new CompletionMessage(Role.SYSTEM, content);
  }

  public static CompletionMessage user(String content) {
    return new CompletionMessage(Role.USER, content);
  }

  public static CompletionMessage assistant(String content) {
    return new CompletionMessage(Role.ASSISTANT, content);
  }
}
