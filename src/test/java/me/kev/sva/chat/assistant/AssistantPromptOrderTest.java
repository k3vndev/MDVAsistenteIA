package me.kev.sva.chat.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import me.kev.sva.ai.CompletionMessage;
import me.kev.sva.ai.CompletionMessage.Role;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.BroadcastChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;

class AssistantPromptOrderTest {

  private static final List<String> STABLE = List.of("CORE", "PERSONA", "OUTPUT", "CAPS");

  private static PlayerChatMessage player(String text) {
    return new PlayerChatMessage(null, UUID.randomUUID(), "Steve", "Steve", false, text);
  }

  private static List<CompletionMessage> assemble(List<ChatMessage> chat, String type) {
    return IsoldaPromptAssembler.assemble(STABLE, "ACTIONS", "LOCAL", "SERVER", "REQUEST", chat, 5, type);
  }

  @Test
  void stableMessagesKeepExactOrderAndContent() {
    List<String> stable = IsoldaPromptAssembler.stableSystemMessages("Be kind.", "  can fly  ", 190, 3, "[CAPS]");
    assertEquals(List.of(
        AssistantContextualizer.PRIMARY_SYSTEM_INSTRUCTIONS,
        AssistantContextualizer.PERSONALITY_PROMPT_HEADER + "Be kind.\n\n[WORLD CAPABILITIES]\ncan fly",
        "[OUTPUT] max_chars=190, max_chat_messages=3. Java enforces both limits.",
        "[CAPS]"), stable);

    List<String> noTools = IsoldaPromptAssembler.stableSystemMessages(null, "", 0, 1, null);
    assertEquals(AssistantContextualizer.PERSONALITY_PROMPT_HEADER, noTools.get(1));
    assertEquals(IsoldaPromptAssembler.CAPABILITIES_DISABLED, noTools.get(3));
  }

  @Test
  void systemPrefixThenDynamicContextThenConversation() {
    PlayerChatMessage steve = player("hello world");
    List<CompletionMessage> out = assemble(List.of(
        new SystemContextMessage(null, "[CTX] ", "ctx"),
        steve,
        new AssistantChatMessage(null, "hi"),
        new BroadcastChatMessage(null, "server restart")), "openai");

    assertEquals(List.of(
        CompletionMessage.system("CORE"),
        CompletionMessage.system("PERSONA"),
        CompletionMessage.system("OUTPUT"),
        CompletionMessage.system("CAPS"),
        CompletionMessage.system("ACTIONS"),
        CompletionMessage.system("LOCAL"),
        CompletionMessage.system("SERVER"),
        CompletionMessage.system("REQUEST"),
        CompletionMessage.system("[CTX] ctx"),
        CompletionMessage.user(steve.header + "hello"),
        CompletionMessage.assistant("hi"),
        CompletionMessage.system("[Global] server restart")), out);
  }

  @Test
  void toolsDisabledUsesPlaceholder() {
    List<CompletionMessage> out = IsoldaPromptAssembler.assemble(
        STABLE, null, "L", "S", "R", List.of(player("x")), 0, "openai");
    assertEquals(CompletionMessage.system(IsoldaPromptAssembler.TOOLS_DISABLED), out.get(4));
  }

  @Test
  void geminiGetsContinuationWithoutUserTurn() {
    List<CompletionMessage> out = assemble(List.of(new SystemContextMessage(null, null, "idle")), "gemini");
    CompletionMessage last = out.get(out.size() - 1);
    assertEquals(Role.USER, last.role());
    assertEquals(IsoldaPromptAssembler.GEMINI_CONTINUATION, last.content());
  }

  @Test
  void geminiGetsContinuationWhenAssistantSpokeLast() {
    List<CompletionMessage> out = assemble(List.of(player("hey"), new AssistantChatMessage(null, "yo")), "gemini");
    assertEquals(IsoldaPromptAssembler.GEMINI_CONTINUATION, out.get(out.size() - 1).content());
  }

  @Test
  void geminiWithoutContinuationWhenPlayerSpokeLast() {
    List<CompletionMessage> out = assemble(List.of(player("hey")), "gemini");
    assertEquals(Role.USER, out.get(out.size() - 1).role());
    assertEquals(9, out.size());
  }

  @Test
  void openaiNeverGetsContinuation() {
    List<CompletionMessage> out = assemble(List.of(new AssistantChatMessage(null, "yo")), "openai");
    assertEquals(CompletionMessage.assistant("yo"), out.get(out.size() - 1));
  }
}
