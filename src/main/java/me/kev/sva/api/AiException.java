package me.kev.sva.api;

/** Why an {@link AiService#request} future failed. */
public final class AiException extends Exception {
  private static final long serialVersionUID = 1L;

  public enum Reason {
    /** api.enabled is false. */
    DISABLED,
    /** No provider is configured. */
    NOT_CONFIGURED,
    /** The calling plugin is not listed (or disabled) under api.clients. */
    CLIENT_NOT_ALLOWED,
    /** The profile does not exist or is not in the client's profile list. */
    PROFILE_NOT_ALLOWED,
    /** The request breaks api.limits or is otherwise malformed. */
    INVALID_REQUEST,
    /** A per-minute limit was reached, or the provider answered 429. */
    RATE_LIMITED,
    /** The client's daily token budget is used up. */
    BUDGET_EXCEEDED,
    /** Too many requests are already waiting. */
    QUEUE_FULL,
    /** The request waited longer than api.max-queue-wait-ms. */
    QUEUE_TIMEOUT,
    /** The provider did not answer in time. */
    TIMEOUT,
    /** The provider returned an error. The cause, if any, is chained. */
    PROVIDER_ERROR,
    /** ServerAssistant was reloaded while the request was pending. */
    RELOADING,
    /** ServerAssistant is shutting down. */
    SHUTDOWN
  }

  private final Reason reason;

  public AiException(Reason reason, String message) {
    this(reason, message, null);
  }

  public AiException(Reason reason, String message, Throwable cause) {
    super(reason + (message == null || message.isBlank() ? "" : ": " + message), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
