package me.kev.sva.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable model call. Build it with {@link #builder(String)}.
 *
 * <p>Put stable instructions first and changing data last: the provider can then
 * reuse its automatic prompt cache for your identical prefix.</p>
 */
public final class AiRequest {
  /** Hard cap checked by {@link Builder#build()}; config limits are checked when queued. */
  public static final int MAX_MESSAGES_HARD_LIMIT = 100;
  public static final String DEFAULT_PROFILE = "default";

  private final String profile;
  private final List<AiMessage> messages;
  private final AiResponseFormat format;
  private final AiPriority priority;
  private final Integer maxOutputTokens;
  private final Double temperature;
  private final UUID playerId;
  private final String tag;

  private AiRequest(Builder builder) {
    this.profile = builder.profile;
    this.messages = List.copyOf(builder.messages);
    this.format = builder.format;
    this.priority = builder.priority;
    this.maxOutputTokens = builder.maxOutputTokens;
    this.temperature = builder.temperature;
    this.playerId = builder.playerId;
    this.tag = builder.tag;
  }

  /** Starts a request for a profile from api.profiles; null/blank means "default". */
  public static Builder builder(String profile) {
    return new Builder(profile);
  }

  public String profile() {
    return profile;
  }

  /** Messages in the order they were added. */
  public List<AiMessage> messages() {
    return messages;
  }

  public AiResponseFormat format() {
    return format;
  }

  public AiPriority priority() {
    return priority;
  }

  /** Requested output limit, or null for the profile's. Clamped to the profile's limit. */
  public Integer maxOutputTokens() {
    return maxOutputTokens;
  }

  /** Requested temperature in [0, 2], or null for the profile's. */
  public Double temperature() {
    return temperature;
  }

  /** Player this request is for (enables per-player limits), or null. */
  public UUID playerId() {
    return playerId;
  }

  /** Free text for logs, e.g. "trivia:generate". Never empty; "-" when unset. */
  public String tag() {
    return tag;
  }

  /** Total characters across all message contents. */
  public int inputChars() {
    int total = 0;
    for (AiMessage message : messages) total += message.content().length();
    return total;
  }

  public static final class Builder {
    private final String profile;
    private final List<AiMessage> messages = new ArrayList<>();
    private AiResponseFormat format = AiResponseFormat.TEXT;
    private AiPriority priority = AiPriority.BACKGROUND;
    private Integer maxOutputTokens;
    private Double temperature;
    private UUID playerId;
    private String tag = "-";

    private Builder(String profile) {
      this.profile = profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile.trim();
    }

    /** Adds a system message. May be called several times; order is preserved. */
    public Builder system(String text) {
      return add(AiRole.SYSTEM, text);
    }

    public Builder user(String text) {
      return add(AiRole.USER, text);
    }

    /** Adds an assistant turn, e.g. for few-shot examples. */
    public Builder assistant(String text) {
      return add(AiRole.ASSISTANT, text);
    }

    public Builder messages(List<AiMessage> list) {
      Objects.requireNonNull(list, "messages");
      for (AiMessage message : list) {
        Objects.requireNonNull(message, "message");
        messages.add(message);
      }
      return this;
    }

    private Builder add(AiRole role, String text) {
      messages.add(new AiMessage(role, text == null ? "" : text));
      return this;
    }

    public Builder format(AiResponseFormat f) {
      this.format = Objects.requireNonNull(f, "format");
      return this;
    }

    /** Default {@link AiPriority#BACKGROUND}. */
    public Builder priority(AiPriority p) {
      this.priority = Objects.requireNonNull(p, "priority");
      return this;
    }

    /** Must be at least 1; clamped to the profile's limit when sent. */
    public Builder maxOutputTokens(int n) {
      if (n < 1) throw new IllegalArgumentException("maxOutputTokens must be >= 1");
      this.maxOutputTokens = n;
      return this;
    }

    /** Clamped to [0, 2]. */
    public Builder temperature(double t) {
      if (Double.isNaN(t)) throw new IllegalArgumentException("temperature is NaN");
      this.temperature = Math.max(0.0D, Math.min(2.0D, t));
      return this;
    }

    /** Enables per-player limits for this request. */
    public Builder forPlayer(UUID id) {
      this.playerId = id;
      return this;
    }

    /** Free text for logs, e.g. "trivia:generate". */
    public Builder tag(String value) {
      this.tag = value == null || value.isBlank() ? "-" : value.trim().replaceAll("\\s+", "_");
      return this;
    }

    /**
     * Validates and builds.
     *
     * @throws IllegalArgumentException if there is no USER message, the last message
     *     is not USER, a message is blank, or there are too many messages
     */
    public AiRequest build() {
      if (messages.isEmpty()) {
        throw new IllegalArgumentException("A request needs at least one user message.");
      }
      if (messages.size() > MAX_MESSAGES_HARD_LIMIT) {
        throw new IllegalArgumentException("Too many messages (max " + MAX_MESSAGES_HARD_LIMIT + ").");
      }
      for (AiMessage message : messages) {
        if (message.content().isBlank()) {
          throw new IllegalArgumentException("Message content must not be blank.");
        }
      }
      if (messages.get(messages.size() - 1).role() != AiRole.USER) {
        throw new IllegalArgumentException("The last message must be a user message.");
      }
      return new AiRequest(this);
    }
  }
}
