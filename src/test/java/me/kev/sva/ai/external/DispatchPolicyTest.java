package me.kev.sva.ai.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.kev.sva.ai.ProviderSlot;
import me.kev.sva.ai.external.DispatchPolicy.Decision;
import me.kev.sva.ai.external.DispatchPolicy.Input;
import me.kev.sva.ai.external.DispatchPolicy.Kind;
import me.kev.sva.ai.external.DispatchPolicy.SlotState;
import me.kev.sva.api.AiException.Reason;
import me.kev.sva.api.AiPriority;

class DispatchPolicyTest {

  private static SlotState primary(long delay, int used, int limit) {
    return new SlotState(ProviderSlot.PRIMARY, true, delay, used, limit);
  }

  private static SlotState fallback(long delay, int used, int limit) {
    return new SlotState(ProviderSlot.FALLBACK, true, delay, used, limit);
  }

  private static Decision decide(AiPriority priority, SlotState preferred, SlotState alternate, long waited) {
    return DispatchPolicy.decide(new Input(priority, preferred, alternate, 4, 2_500L, waited, 15_000L));
  }

  @Test
  void freeProviderGoesNow() {
    Decision d = decide(AiPriority.BACKGROUND, primary(0, 0, 12), null, 0);
    assertEquals(Kind.GO, d.kind());
    assertEquals(ProviderSlot.PRIMARY, d.slot());
  }

  @Test
  void backgroundWaitsToKeepIsoldasReserve() {
    // 8 of 12 used; reserve 4 -> background must wait, interactive may go.
    Decision background = decide(AiPriority.BACKGROUND, primary(0, 8, 12), null, 0);
    assertEquals(Kind.WAIT, background.kind());
    assertTrue(background.waitMs() > 0);

    Decision interactive = decide(AiPriority.INTERACTIVE, primary(0, 8, 12), null, 0);
    assertEquals(Kind.GO, interactive.kind());

    // Unlimited provider: no reserve applies.
    assertEquals(Kind.GO, decide(AiPriority.BACKGROUND, primary(0, 50, 0), null, 0).kind());
    // 7 of 12 used: still room.
    assertEquals(Kind.GO, decide(AiPriority.BACKGROUND, primary(0, 7, 12), null, 0).kind());
  }

  @Test
  void reserveIsCappedAtHalfOfSmallBuckets() {
    // limit 4, reserve 4 -> effective reserve 2: two background requests per minute.
    assertEquals(Kind.GO, decide(AiPriority.BACKGROUND, fallback(0, 1, 4), null, 0).kind());
    assertEquals(Kind.WAIT, decide(AiPriority.BACKGROUND, fallback(0, 2, 4), null, 0).kind());
    assertEquals(0, DispatchPolicy.effectiveReserve(4, 1));
    assertEquals(4, DispatchPolicy.effectiveReserve(4, 12));
  }

  @Test
  void delayedPrimarySwitchesToFreeFallback() {
    Decision d = decide(AiPriority.INTERACTIVE, primary(10_000, 0, 12), fallback(0, 0, 4), 0);
    assertEquals(Kind.GO, d.kind());
    assertEquals(ProviderSlot.FALLBACK, d.slot());
  }

  @Test
  void delayedPrimaryWithoutFallbackWaits() {
    Decision d = decide(AiPriority.INTERACTIVE, primary(3_000, 0, 12), null, 0);
    assertEquals(Kind.WAIT, d.kind());
    assertEquals(3_000L, d.waitMs());
  }

  @Test
  void busyFallbackBeyondMaxWaitIsIgnored() {
    Decision d = decide(AiPriority.INTERACTIVE, primary(10_000, 0, 12), fallback(5_000, 0, 4), 0);
    assertEquals(Kind.WAIT, d.kind());
    assertEquals(10_000L, d.waitMs());
  }

  @Test
  void unavailablePreferredUsesAlternate() {
    SlotState missing = new SlotState(ProviderSlot.PRIMARY, false, 0, 0, 0);
    Decision d = decide(AiPriority.BACKGROUND, missing, fallback(0, 0, 4), 0);
    assertEquals(Kind.GO, d.kind());
    assertEquals(ProviderSlot.FALLBACK, d.slot());

    Decision none = decide(AiPriority.BACKGROUND, missing, null, 0);
    assertEquals(Kind.FAIL, none.kind());
    assertEquals(Reason.NOT_CONFIGURED, none.reason());
  }

  @Test
  void queueTimeout() {
    Decision d = decide(AiPriority.BACKGROUND, primary(0, 0, 12), null, 15_000);
    assertEquals(Kind.FAIL, d.kind());
    assertEquals(Reason.QUEUE_TIMEOUT, d.reason());
  }
}
