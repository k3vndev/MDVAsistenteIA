package me.kev.sva.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AiRequestTest {

  @Test
  void keepsMessageOrderAndDefaults() {
    AiRequest request = AiRequest.builder(null)
        .system("rules")
        .user("q1")
        .assistant("a1")
        .system("more rules")
        .user("q2")
        .build();
    assertEquals("default", request.profile());
    assertEquals(List.of(
        new AiMessage(AiRole.SYSTEM, "rules"),
        new AiMessage(AiRole.USER, "q1"),
        new AiMessage(AiRole.ASSISTANT, "a1"),
        new AiMessage(AiRole.SYSTEM, "more rules"),
        new AiMessage(AiRole.USER, "q2")), request.messages());
    assertEquals(AiPriority.BACKGROUND, request.priority());
    assertEquals(AiResponseFormat.TEXT, request.format());
    assertNull(request.maxOutputTokens());
    assertNull(request.temperature());
    assertNull(request.playerId());
    assertEquals("-", request.tag());
    assertEquals(21, request.inputChars());
  }

  @Test
  void blankProfileMeansDefault() {
    assertEquals("default", AiRequest.builder("  ").user("x").build().profile());
    assertEquals("trivia-generate", AiRequest.builder(" trivia-generate ").user("x").build().profile());
  }

  @Test
  void clampsTemperatureAndStoresOptions() {
    UUID player = UUID.randomUUID();
    AiRequest request = AiRequest.builder("p")
        .user("x")
        .temperature(5.0)
        .maxOutputTokens(50)
        .forPlayer(player)
        .priority(AiPriority.INTERACTIVE)
        .format(AiResponseFormat.JSON_OBJECT)
        .tag("trivia: generate")
        .build();
    assertEquals(2.0, request.temperature());
    assertEquals(50, request.maxOutputTokens());
    assertEquals(player, request.playerId());
    assertEquals(AiPriority.INTERACTIVE, request.priority());
    assertEquals(AiResponseFormat.JSON_OBJECT, request.format());
    assertEquals("trivia:_generate", request.tag());
    assertEquals(0.0, AiRequest.builder("p").user("x").temperature(-1).build().temperature());
  }

  @Test
  void rejectsInvalidRequests() {
    assertThrows(IllegalArgumentException.class, () -> AiRequest.builder("p").build());
    assertThrows(IllegalArgumentException.class, () -> AiRequest.builder("p").system("only system").build());
    assertThrows(IllegalArgumentException.class, () -> AiRequest.builder("p").user("q").assistant("a").build());
    assertThrows(IllegalArgumentException.class, () -> AiRequest.builder("p").user("   ").build());
    assertThrows(IllegalArgumentException.class, () -> AiRequest.builder("p").user("q").maxOutputTokens(0));
    AiRequest.Builder many = AiRequest.builder("p");
    for (int i = 0; i <= AiRequest.MAX_MESSAGES_HARD_LIMIT; i++) many.user("m" + i);
    assertThrows(IllegalArgumentException.class, many::build);
  }
}
