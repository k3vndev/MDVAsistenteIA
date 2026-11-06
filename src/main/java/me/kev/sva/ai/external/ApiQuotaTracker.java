package me.kev.sva.ai.external;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import me.kev.sva.api.AiException;

/**
 * Per-minute and daily limits for external API clients.
 *
 * <p>Lives as long as the plugin (survives /sva reload) but only in memory: daily
 * budgets restart with the server. The budget is checked against tokens already
 * used, so one request can go slightly over it.</p>
 *
 * <p>Main thread only.</p>
 */
public final class ApiQuotaTracker {
  private static final long WINDOW_MS = 60_000L;

  private final LongSupplier clock;
  private final ZoneId zone;
  private final Deque<Long> global = new ArrayDeque<>();
  private final Map<String, Deque<Long>> perClient = new HashMap<>();
  private final Map<String, Deque<Long>> perPlayer = new HashMap<>();
  private final Map<String, Long> tokensToday = new HashMap<>();
  private LocalDate day;

  public ApiQuotaTracker(LongSupplier clock, ZoneId zone) {
    this.clock = clock;
    this.zone = zone;
    this.day = today();
  }

  public ApiQuotaTracker() {
    this(System::currentTimeMillis, ZoneId.systemDefault());
  }

  /**
   * Null if the request may be admitted, otherwise why not.
   *
   * @param globalRpm external requests per minute for all clients together (0 = no cap)
   */
  public AiException.Reason checkAdmission(ApiClientPolicy policy, UUID playerId, int globalRpm) {
    long now = clock.getAsLong();
    rollDay();
    String client = key(policy.name());
    if (globalRpm > 0 && count(global, now) >= globalRpm) return AiException.Reason.RATE_LIMITED;
    if (policy.maxRequestsPerMinute() > 0
        && count(perClient.get(client), now) >= policy.maxRequestsPerMinute()) {
      return AiException.Reason.RATE_LIMITED;
    }
    if (playerId != null && policy.maxRequestsPerPlayerPerMinute() > 0
        && count(perPlayer.get(playerKey(client, playerId)), now) >= policy.maxRequestsPerPlayerPerMinute()) {
      return AiException.Reason.RATE_LIMITED;
    }
    if (policy.dailyTokenBudget() > 0 && tokensToday.getOrDefault(client, 0L) >= policy.dailyTokenBudget()) {
      return AiException.Reason.BUDGET_EXCEEDED;
    }
    return null;
  }

  /** Counts one admitted request in every per-minute window. */
  public void recordStart(String clientName, UUID playerId) {
    long now = clock.getAsLong();
    String client = key(clientName);
    global.addLast(now);
    perClient.computeIfAbsent(client, k -> new ArrayDeque<>()).addLast(now);
    if (playerId != null) {
      perPlayer.computeIfAbsent(playerKey(client, playerId), k -> new ArrayDeque<>()).addLast(now);
    }
  }

  /** Adds input + output tokens to today's total for the client. */
  public void recordUsage(String clientName, long tokens) {
    if (tokens <= 0) return;
    rollDay();
    tokensToday.merge(key(clientName), tokens, Long::sum);
  }

  public Snapshot snapshot(String clientName) {
    long now = clock.getAsLong();
    rollDay();
    String client = key(clientName);
    return new Snapshot(count(perClient.get(client), now), tokensToday.getOrDefault(client, 0L));
  }

  public int globalRequestsLastMinute() {
    return count(global, clock.getAsLong());
  }

  public record Snapshot(int requestsLastMinute, long tokensToday) {
  }

  private void rollDay() {
    LocalDate current = today();
    if (!current.equals(day)) {
      day = current;
      tokensToday.clear();
    }
  }

  private LocalDate today() {
    return Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate();
  }

  private static int count(Deque<Long> times, long now) {
    if (times == null) return 0;
    while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MS) times.removeFirst();
    return times.size();
  }

  private static String key(String name) {
    return name == null ? "" : name.toLowerCase(Locale.ROOT);
  }

  private static String playerKey(String client, UUID playerId) {
    return client + "|" + playerId;
  }
}
