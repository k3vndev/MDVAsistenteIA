package me.kev.sva.chat.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiConsumer;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.ai.AiGateway;
import me.kev.sva.ai.CompletionMessage;
import me.kev.sva.ai.CompletionRequest;
import me.kev.sva.ai.ProviderSettings;
import me.kev.sva.ai.ProviderSlot;
import me.kev.sva.chat.message.ChatMessage;

/**
 * Isolda's side of a model call: stable prompt prefix, personality, tools, scene
 * context, the m/t JSON envelope, Gemini's continuation and prompt_cache_key.
 *
 * <p>Model access (clients, executor, watchdog, throttle, token logs) lives in
 * {@link AiGateway}, which is owned by the plugin and shared with the external AI API.</p>
 */
public class AssistantManager {
  public static final int PRIMARY = 0;
  public static final int FALLBACK = 1;

  private final ServerAssistantPlugin plugin;
  private final AiGateway gateway;
  /** Exact stable system prefix reused by every request until /sva reload. */
  private final List<String> stableSystemMessages;
  /** Hash of the exact stable prefix; used only to rotate OpenAI's routing key safely. */
  private final String stableSystemHash;
  private volatile boolean shutdown = false;

  public AssistantManager(ServerAssistantPlugin plugin, AiGateway gateway) {
    this.plugin = plugin;
    this.gateway = gateway;

    // Build this once per plugin load/reload. Besides avoiding repeated YAML/string work,
    // preserving these messages byte-for-byte at the front of each OpenAI request gives
    // automatic prompt caching the longest possible identical prefix.
    this.stableSystemMessages = buildStableSystemMessages();
    this.stableSystemHash = shortSha256(String.join("\n\n---\n\n", stableSystemMessages));

    ProviderSettings primary = gateway.settings(ProviderSlot.PRIMARY);
    if (isOpenAIPromptCacheEnabled(primary)) {
      plugin.getLogger().info("OpenAI prompt cache routing enabled (stable prefix key="
          + promptCacheKey(primary) + ").");
    }
  }

  public AiGateway gateway() {
    return gateway;
  }

  public ProviderSettings getProviderSettings(int providerIndex) {
    return gateway.settings(ProviderSlot.fromIndex(providerIndex));
  }

  public ProviderSettings getPrimaryProviderSettings() {
    return gateway.settings(ProviderSlot.PRIMARY);
  }

  public ProviderSettings getFallbackProviderSettings() {
    return gateway.settings(ProviderSlot.FALLBACK);
  }

  public boolean isProviderAvailable(int providerIndex) {
    return gateway.isAvailable(ProviderSlot.fromIndex(providerIndex));
  }

  public boolean hasFallback() {
    return gateway.hasFallback();
  }

  /** Stops new requests. The gateway (and its clients) belongs to the plugin. */
  public void shutdown() {
    shutdown = true;
  }

  /**
   * Builds the request synchronously from trusted main-thread snapshots; the gateway
   * performs only the network call off-thread. Completion always returns to the
   * Bukkit main thread, exactly once.
   */
  public void sendAIRequest(
      int providerIndex,
      List<ChatMessage> chatMessages,
      AssistantRequestContext requestContext,
      BiConsumer<AssistantResponse, Throwable> completion) {

    if (shutdown) {
      completion.accept(null, new IllegalStateException("Assistant manager is shut down."));
      return;
    }

    ProviderSlot slot = ProviderSlot.fromIndex(providerIndex);
    ProviderSettings provider = gateway.settings(slot);
    if (provider == null) {
      completion.accept(null, new IllegalStateException("Selected AI provider is not configured."));
      return;
    }

    List<CompletionMessage> messages = IsoldaPromptAssembler.assemble(
        stableSystemMessages,
        plugin.getToolManager() == null
            ? null
            : plugin.getToolManager().getAvailableActionToolsPrompt(requestContext.currentActionText()),
        AssistantContextualizer.getLocalKnowledge(requestContext),
        AssistantContextualizer.getServerContext(),
        AssistantContextualizer.getRequestContext(requestContext),
        chatMessages,
        Math.max(plugin.getConfig().getInt("chat.max-player-message-length", 220), 0),
        provider.type());

    long sceneId = requestContext == null ? -1L : requestContext.sceneId();
    String players = requestContext == null ? "" : AiGateway.oneLineForUsageLog(requestContext.involvedPlayers());

    CompletionRequest request = new CompletionRequest(
        messages,
        null,
        null,
        null,
        null,
        // OpenAI JSON mode guarantees a syntactically valid JSON object. This matters
        // for one-call ACTION tools because a plain-text fallback cannot carry `t`.
        // The gateway applies it only to type=openai.
        plugin.getConfig().getBoolean("provider-response.force-json-object-openai", true),
        isOpenAIPromptCacheEnabled(provider) ? promptCacheKey(provider) : null,
        "scene=" + (sceneId < 0 ? "?" : Long.toString(sceneId)),
        players.isBlank() ? "" : "players=" + players);

    gateway.complete(slot, request, (result, error) -> {
      String text = result == null ? "" : result.text();
      AssistantResponse parsed;
      try {
        parsed = text.isBlank()
            ? new AssistantResponse(plugin, List.of(), List.of(), false)
            : new AssistantResponse(plugin, text);
      } catch (RuntimeException parseError) {
        completion.accept(null, error != null ? error : parseError);
        return;
      }
      completion.accept(parsed, error);
    });
  }

  /**
   * Builds the exact reusable front of the prompt once per load/reload.
   *
   * <p>Do not put timestamps, scene IDs, selected wiki chunks, player identities or
   * current ACTION permissions here. Those belong after this prefix.</p>
   */
  private List<String> buildStableSystemMessages() {
    String personalityPrompt = plugin.getPersonalityConfig().getString(
        "prompt",
        AssistantContextualizer.DEFAULT_PERSONALITY_PROMPT);
    String capabilitiesNote = plugin.getPersonalityConfig().getString("capabilities-note", "");
    int maxAssistantMessageLength = Math.max(
        plugin.getConfig().getInt("chat.max-assistant-message-length", 190),
        0);
    int maxAssistantMessages = Math.max(
        plugin.getConfig().getInt("chat.max-messages-per-response", 3),
        1);
    return IsoldaPromptAssembler.stableSystemMessages(
        personalityPrompt,
        capabilitiesNote,
        maxAssistantMessageLength,
        maxAssistantMessages,
        plugin.getToolManager() == null ? null : plugin.getToolManager().getCapabilitiesPrompt());
  }

  private boolean isOpenAIPromptCacheEnabled(ProviderSettings provider) {
    return provider != null
        && "openai".equalsIgnoreCase(provider.type())
        && plugin.getConfig().getBoolean("ai.prompt-cache.enabled", true);
  }

  /**
   * Stable routing key for requests with this exact loaded prefix/model.
   * Changing personality or /sva reload rotates the suffix automatically.
   */
  private String promptCacheKey(ProviderSettings provider) {
    String configured = plugin.getConfig().getString(
        "ai.prompt-cache.key-prefix",
        "mdvcraft-isolda");
    String prefix = sanitizeCacheKeyPrefix(configured);
    String model = provider == null || provider.model() == null ? "unknown" : provider.model();
    String modelHash = shortSha256(model);
    return prefix + "-" + modelHash.substring(0, 8) + "-" + stableSystemHash.substring(0, 16);
  }

  private static String sanitizeCacheKeyPrefix(String configured) {
    String value = configured == null ? "" : configured.trim();
    value = value.replaceAll("[^A-Za-z0-9:_-]+", "-")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "");
    if (value.isBlank()) value = "mdvcraft-isolda";
    // Keep plenty of room for the two short hashes and separators.
    if (value.length() > 36) value = value.substring(0, 36);
    return value;
  }

  private static String shortSha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is required by the Java platform; this is only a defensive fallback.
      return String.format("%064x", (long) (value == null ? 0 : value.hashCode()));
    }
  }
}
