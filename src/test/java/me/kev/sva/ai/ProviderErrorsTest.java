package me.kev.sva.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class ProviderErrorsTest {

  static final class RateLimitException extends RuntimeException {
    RateLimitException() {
      super("slow down");
    }
  }

  @Test
  void recognizesRateLimits() {
    assertTrue(ProviderErrors.isRateLimit(new RateLimitException()));
    assertTrue(ProviderErrors.isRateLimit(new RuntimeException("HTTP 429 Too Many Requests")));
    assertTrue(ProviderErrors.isRateLimit(new RuntimeException("RESOURCE_EXHAUSTED: quota")));
    assertTrue(ProviderErrors.isRateLimit(new RuntimeException("Rate limit reached")));
    assertFalse(ProviderErrors.isRateLimit(new RuntimeException("bad request 400")));
  }

  @Test
  void recognizesTransientErrors() {
    for (String code : new String[] {"500", "502", "503", "504"}) {
      assertTrue(ProviderErrors.isTransient(new RuntimeException("status " + code)), code);
    }
    assertTrue(ProviderErrors.isTransient(new RuntimeException("request timed out")));
    assertTrue(ProviderErrors.isTransient(new TimeoutException("x")));
    assertTrue(ProviderErrors.isTransient(new RuntimeException("connection reset by peer")));
    assertFalse(ProviderErrors.isTransient(new RuntimeException("invalid api key")));
  }

  @Test
  void parsesRetryDelay() {
    assertEquals(12_250L, ProviderErrors.parseRetryDelay(new RuntimeException("Please retry after 12s"), 60_000L));
    assertEquals(1_750L, ProviderErrors.parseRetryDelay(new RuntimeException("try again in 1.5 seconds"), 60_000L));
    assertEquals(60_000L, ProviderErrors.parseRetryDelay(new RuntimeException("nothing"), 60_000L));
    assertEquals(1_000L, ProviderErrors.parseRetryDelay(new RuntimeException("nothing"), 10L));
  }

  @Test
  void usageLineMatchesLegacyFormat() {
    assertEquals(
        "AI token usage: scene=42 provider=OpenAI model=gpt-4o-mini input=100 cached=64 output=20 total=120 players=Steve, Alex",
        AiGateway.formatUsageLine("OpenAI", "gpt-4o-mini", "scene=42", "players=Steve, Alex",
            new TokenUsage(100, 64, 20)));
    assertEquals(
        "AI token usage: scene=? provider=Gemini model=m input=1 cached=0 output=2 total=3",
        AiGateway.formatUsageLine("Gemini", "m", "scene=?", "", new TokenUsage(1, 0, 2)));
    assertEquals(
        "AI token usage: scene=7 provider=Gemini model=m usage=unavailable",
        AiGateway.formatUsageLine("Gemini", "m", "scene=7", "players=x", null));
  }
}
