package me.kev.sva.ai.external;

import me.kev.sva.ai.ProviderSlot;
import me.kev.sva.api.AiException;
import me.kev.sva.api.AiPriority;

/**
 * Pure decision: may the next queued external request leave now, and on which provider?
 */
public final class DispatchPolicy {
  /** How often a request blocked only by Isolda's reserve re-checks the window. */
  static final long RESERVE_RECHECK_MS = 1_000L;

  private DispatchPolicy() {
  }

  /** Live state of one provider slot, for the effective model of this request. */
  public record SlotState(
      ProviderSlot slot,
      boolean available,
      long delayMs,
      int requestsLastMinute,
      int maxRequestsPerMinute) {
  }

  /**
   * @param preferred      slot the profile (or a forced fallback retry) wants
   * @param alternate      the other slot, or null when switching is not allowed
   * @param waitedMs       time already spent in the queue
   * @param maxFallbackWaitMs only switch to the alternate if its delay is at most this
   */
  public record Input(
      AiPriority priority,
      SlotState preferred,
      SlotState alternate,
      int reserveForAssistantRpm,
      long maxFallbackWaitMs,
      long waitedMs,
      long maxQueueWaitMs) {
  }

  public enum Kind { GO, WAIT, FAIL }

  public record Decision(Kind kind, ProviderSlot slot, long waitMs, AiException.Reason reason) {
    static Decision go(ProviderSlot slot) {
      return new Decision(Kind.GO, slot, 0L, null);
    }

    static Decision waitFor(long ms) {
      return new Decision(Kind.WAIT, null, Math.max(50L, ms), null);
    }

    static Decision fail(AiException.Reason reason) {
      return new Decision(Kind.FAIL, null, 0L, reason);
    }
  }

  public static Decision decide(Input in) {
    if (in.maxQueueWaitMs() > 0 && in.waitedMs() >= in.maxQueueWaitMs()) {
      return Decision.fail(AiException.Reason.QUEUE_TIMEOUT);
    }

    boolean preferredOk = in.preferred() != null && in.preferred().available();
    boolean alternateOk = in.alternate() != null && in.alternate().available();
    if (!preferredOk && !alternateOk) {
      return Decision.fail(AiException.Reason.NOT_CONFIGURED);
    }

    long preferredBlock = preferredOk ? blockedMs(in.preferred(), in) : Long.MAX_VALUE;
    if (preferredBlock == 0L) {
      return Decision.go(in.preferred().slot());
    }

    long alternateBlock = Long.MAX_VALUE;
    if (alternateOk) {
      long block = blockedMs(in.alternate(), in);
      // Like Isolda: only consider the alternate when it frees up soon enough,
      // unless the preferred provider is not usable at all.
      if (!preferredOk || block <= in.maxFallbackWaitMs()) alternateBlock = block;
    }
    if (alternateBlock == 0L) {
      return Decision.go(in.alternate().slot());
    }

    long wait = Math.min(preferredBlock, alternateBlock);
    if (wait == Long.MAX_VALUE) wait = RESERVE_RECHECK_MS;
    return Decision.waitFor(wait);
  }

  /**
   * Requests per minute kept free for Isolda on a provider. Capped at half the
   * provider's limit so a small bucket (e.g. a 4 rpm fallback) still lets some
   * BACKGROUND work through.
   */
  static int effectiveReserve(int reserve, int maxRequestsPerMinute) {
    if (reserve <= 0 || maxRequestsPerMinute <= 0) return 0;
    return Math.min(reserve, maxRequestsPerMinute / 2);
  }

  /** 0 if the slot can take this request now, otherwise a re-check delay. */
  static long blockedMs(SlotState state, Input in) {
    long delay = Math.max(0L, state.delayMs());
    int reserve = effectiveReserve(in.reserveForAssistantRpm(), state.maxRequestsPerMinute());
    if (in.priority() == AiPriority.BACKGROUND
        && reserve > 0
        && state.requestsLastMinute() >= state.maxRequestsPerMinute() - reserve) {
      return Math.max(delay, RESERVE_RECHECK_MS);
    }
    return delay;
  }
}
