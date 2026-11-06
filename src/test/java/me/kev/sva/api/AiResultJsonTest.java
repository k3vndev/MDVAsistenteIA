package me.kev.sva.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiResultJsonTest {

  private static AiResult result(String text) {
    return new AiResult(text, "OpenAI", "m", 0, 0, 0, 0L, false);
  }

  @Test
  void parsesCleanJson() {
    assertEquals("b", result("{\"a\":\"b\"}").json().orElseThrow().get("a").getAsString());
  }

  @Test
  void parsesFencedJson() {
    String text = "```json\n{\"q\": \"Who?\", \"n\": 3}\n```";
    assertEquals(3, result(text).json().orElseThrow().get("n").getAsInt());
  }

  @Test
  void parsesJsonWithSurroundingTextAndBracesInStrings() {
    String text = "Sure! Here it is: {\"q\": \"a } tricky { one\", \"o\": {\"x\": 1}} hope it helps {not json}";
    var json = result(text).json().orElseThrow();
    assertEquals("a } tricky { one", json.get("q").getAsString());
    assertEquals(1, json.getAsJsonObject("o").get("x").getAsInt());
  }

  @Test
  void invalidOrMissingJsonIsEmpty() {
    assertTrue(result("no json here").json().isEmpty());
    assertTrue(result("{broken: ").json().isEmpty());
    assertTrue(result("{\"a\": }").json().isEmpty());
    assertTrue(result("").json().isEmpty());
    assertTrue(result(null).json().isEmpty());
  }
}
