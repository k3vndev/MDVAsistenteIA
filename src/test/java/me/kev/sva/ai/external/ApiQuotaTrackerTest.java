package me.kev.sva.ai.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import me.kev.sva.api.AiException.Reason;

class ApiQuotaTrackerTest {
  private static final ZoneId ZONE = ZoneOffset.UTC;

  private final AtomicLong now = new AtomicLong(
      LocalDateTime.of(2026, 11, 6, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
  private final ApiQuotaTracker tracker = new ApiQuotaTracker(now::get, ZONE);

  private static ApiClientPolicy policy(int rpm, int perPlayer, long budget) {
    return new ApiClientPolicy("Trivia", true, List.of(), rpm, perPlayer, budget);
  }

  @Test
  void perMinuteWindowSlides() {
    ApiClientPolicy policy = policy(2, 0, 0);
    tracker.recordStart("Trivia", null);
    now.addAndGet(10_000);
    tracker.recordStart("trivia", null);
    assertEquals(Reason.RATE_LIMITED, tracker.checkAdmission(policy, null, 0));
    now.addAndGet(50_001); // first request leaves the 60 s window
    assertNull(tracker.checkAdmission(policy, null, 0));
    assertEquals(1, tracker.snapshot("Trivia").requestsLastMinute());
  }

  @Test
  void perPlayerLimitIsIndependentPerPlayer() {
    ApiClientPolicy policy = policy(0, 1, 0);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    tracker.recordStart("Trivia", a);
    assertEquals(Reason.RATE_LIMITED, tracker.checkAdmission(policy, a, 0));
    assertNull(tracker.checkAdmission(policy, b, 0));
    assertNull(tracker.checkAdmission(policy, null, 0));
  }

  @Test
  void globalCapCountsAllClients() {
    tracker.recordStart("Other", null);
    tracker.recordStart("Trivia", null);
    assertEquals(Reason.RATE_LIMITED, tracker.checkAdmission(policy(0, 0, 0), null, 2));
    assertNull(tracker.checkAdmission(policy(0, 0, 0), null, 3));
    assertNull(tracker.checkAdmission(policy(0, 0, 0), null, 0));
  }

  @Test
  void dailyBudgetResetsOnNewDay() {
    ApiClientPolicy policy = policy(0, 0, 1000);
    tracker.recordUsage("Trivia", 600);
    assertNull(tracker.checkAdmission(policy, null, 0));
    tracker.recordUsage("Trivia", 400);
    assertEquals(Reason.BUDGET_EXCEEDED, tracker.checkAdmission(policy, null, 0));
    assertEquals(1000, tracker.snapshot("Trivia").tokensToday());

    now.set(LocalDateTime.of(2026, 11, 7, 0, 0, 1).toInstant(ZoneOffset.UTC).toEpochMilli());
    assertNull(tracker.checkAdmission(policy, null, 0));
    assertEquals(0, tracker.snapshot("Trivia").tokensToday());
  }
}
