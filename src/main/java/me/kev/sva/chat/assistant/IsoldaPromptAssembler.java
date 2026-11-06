package me.kev.sva.chat.assistant;

import java.util.ArrayList;
import java.util.List;

import me.kev.sva.ai.CompletionMessage;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.BroadcastChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;

/**
 * Pure assembly of Isolda's prompt. No Bukkit or config access, so the exact
 * message order can be tested without a server.
 *
 * <p>Order (must stay byte-for-byte stable for OpenAI prompt caching):
 * stable system prefix, ACTION list, local knowledge, server context, request
 * context, conversation turns, then Gemini's continuation if needed.</p>
 */
final class IsoldaPromptAssembler {
  static final String TOOLS_DISABLED = "[TOOLS] disabled; t must be []";
  static final String CAPABILITIES_DISABLED = "[CAPABILITIES] no local tool capabilities available";
  static final String GEMINI_CONTINUATION =
      "[TRUSTED CONTINUATION] Use the trusted context above. Reply only if useful; otherwise stay silent.";

  private IsoldaPromptAssembler() {
  }

  /**
   * Builds the exact reusable front of the prompt.
   *
   * @param capabilitiesPrompt null when tools are unavailable
   */
  static List<String> stableSystemMessages(
      String personalityPrompt,
      String capabilitiesNote,
      int maxAssistantMessageLength,
      int maxAssistantMessages,
      String capabilitiesPrompt) {
    List<String> messages = new ArrayList<>();
    messages.add(AssistantContextualizer.PRIMARY_SYSTEM_INSTRUCTIONS);

    StringBuilder personalitySystem = new StringBuilder(
        AssistantContextualizer.PERSONALITY_PROMPT_HEADER)
        .append(personalityPrompt == null ? "" : personalityPrompt);
    if (capabilitiesNote != null && !capabilitiesNote.isBlank()) {
      personalitySystem.append("\n\n[WORLD CAPABILITIES]\n").append(capabilitiesNote.trim());
    }
    messages.add(personalitySystem.toString());

    messages.add(
        "[OUTPUT] max_chars=" + maxAssistantMessageLength
            + ", max_chat_messages=" + maxAssistantMessages + ". Java enforces both limits.");

    // This capability catalogue is stable across scenes. Only the per-scene ACTION
    // allow-list is dynamic and is appended later.
    messages.add(capabilitiesPrompt != null ? capabilitiesPrompt : CAPABILITIES_DISABLED);
    return List.copyOf(messages);
  }

  /**
   * @param actionToolsPrompt null when tools are unavailable
   */
  static List<CompletionMessage> assemble(
      List<String> stableSystemMessages,
      String actionToolsPrompt,
      String localKnowledge,
      String serverContext,
      String requestContext,
      List<ChatMessage> chatMessages,
      int maxPlayerMessageLength,
      String providerType) {
    List<CompletionMessage> out = new ArrayList<>();

    // IMPORTANT: nothing request-specific may be inserted before these messages.
    // OpenAI cache hits require an exact prompt prefix match.
    for (String message : stableSystemMessages) {
      out.add(CompletionMessage.system(message));
    }

    // ACTION availability changes from scene to scene, so it deliberately starts the
    // dynamic suffix. Keeping it out of the stable prefix avoids invalidating the large
    // CORE/personality prefix whenever a scene requests a different action.
    out.add(CompletionMessage.system(actionToolsPrompt != null ? actionToolsPrompt : TOOLS_DISABLED));

    // Dynamic/local context follows the cache-friendly prefix. Wiki/player/inventory
    // retrieval happens in Java before this one model call. Action tools execute
    // from the same response, so there is no model tool loop.
    out.add(CompletionMessage.system(localKnowledge));
    out.add(CompletionMessage.system(serverContext));
    out.add(CompletionMessage.system(requestContext));

    appendConversation(out, chatMessages, maxPlayerMessageLength, providerType);
    return out;
  }

  private static void appendConversation(
      List<CompletionMessage> out,
      List<ChatMessage> chatMessages,
      int maxPlayerMessageLength,
      String providerType) {
    boolean hasUserTurn = false;
    boolean lastConversationalTurnWasAssistant = false;

    for (ChatMessage message : chatMessages) {
      if (message instanceof AssistantChatMessage assistantMessage) {
        out.add(CompletionMessage.assistant(assistantMessage.content));
        lastConversationalTurnWasAssistant = true;
        continue;
      }

      if (message instanceof PlayerChatMessage playerMessage) {
        String msg = message.content;
        if (maxPlayerMessageLength > 0 && msg.length() > maxPlayerMessageLength) {
          msg = msg.substring(0, maxPlayerMessageLength);
        }

        // Player text is a real user turn instead of a system message. This is both
        // more natural for small models and safer against prompt injection.
        out.add(CompletionMessage.user(playerMessage.header + msg));
        hasUserTurn = true;
        lastConversationalTurnWasAssistant = false;
        continue;
      }

      if (message instanceof SystemContextMessage systemMessage) {
        out.add(CompletionMessage.system(systemMessage.header + systemMessage.content));
        continue;
      }

      if (message instanceof BroadcastChatMessage broadcastMessage) {
        out.add(CompletionMessage.system(broadcastMessage.header + broadcastMessage.content));
      }
    }

    // Gemini's OpenAI-compatibility endpoint can reject an effective sequence
    // ending on the model. OpenAI does not need this synthetic continuation.
    if ("gemini".equalsIgnoreCase(providerType)
        && (!hasUserTurn || lastConversationalTurnWasAssistant)) {
      out.add(CompletionMessage.user(GEMINI_CONTINUATION));
    }
  }
}
