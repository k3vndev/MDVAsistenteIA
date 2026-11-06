package me.kev.sva.ai.external;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

import me.kev.sva.ai.CompletionRequest;
import me.kev.sva.ai.ProviderSlot;

/** Immutable snapshot of the api.* config section. */
public final class ApiSettings {
  public static final String DEFAULT_PROFILE = "default";
  public static final String DEFAULT_BASE_SYSTEM_PROMPT =
      "You are a backend helper for a Minecraft server plugin. Follow the plugin's instructions below. "
          + "Never claim to be Isolda unless asked.";

  private final boolean enabled;
  private final int maxConcurrentRequests;
  private final int maxRequestsPerMinute;
  private final int reserveForAssistantRpm;
  private final long maxQueueWaitMs;
  private final int maxQueueSize;
  private final boolean allowUnknownClients;
  private final boolean logRequests;
  private final int maxMessages;
  private final int maxInputChars;
  private final String baseSystemPrompt;
  /** Keyed by lower-case profile name. Always contains "default". */
  private final Map<String, ApiProfile> profiles;
  /** Keyed by lower-case plugin name. */
  private final Map<String, ApiClientPolicy> clients;

  private ApiSettings(ConfigurationSection api) {
    this.enabled = api == null || api.getBoolean("enabled", true);
    this.maxConcurrentRequests = Math.max(get(api, "max-concurrent-requests", 2), 1);
    this.maxRequestsPerMinute = Math.max(get(api, "max-requests-per-minute", 6), 0);
    this.reserveForAssistantRpm = Math.max(get(api, "reserve-for-assistant-rpm", 4), 0);
    this.maxQueueWaitMs = Math.max(api == null ? 15_000L : api.getLong("max-queue-wait-ms", 15_000L), 0L);
    this.maxQueueSize = Math.max(get(api, "max-queue-size", 20), 1);
    String unknown = api == null ? "deny" : api.getString("unknown-clients", "deny");
    this.allowUnknownClients = "allow".equalsIgnoreCase(unknown == null ? "" : unknown.trim());
    this.logRequests = api == null || api.getBoolean("log-requests", true);
    this.maxMessages = Math.max(get(api, "limits.max-messages", 20), 1);
    this.maxInputChars = Math.max(get(api, "limits.max-input-chars", 12_000), 1);
    String base = api == null ? DEFAULT_BASE_SYSTEM_PROMPT
        : api.getString("base-system-prompt", DEFAULT_BASE_SYSTEM_PROMPT);
    this.baseSystemPrompt = base == null ? "" : base.trim();
    this.profiles = Collections.unmodifiableMap(loadProfiles(api == null ? null : api.getConfigurationSection("profiles")));
    this.clients = Collections.unmodifiableMap(loadClients(api == null ? null : api.getConfigurationSection("clients")));
  }

  /** Loads from the plugin's root config (reads the "api" section). */
  public static ApiSettings load(ConfigurationSection root) {
    return new ApiSettings(root == null ? null : root.getConfigurationSection("api"));
  }

  private static int get(ConfigurationSection section, String path, int fallback) {
    return section == null ? fallback : section.getInt(path, fallback);
  }

  private static Map<String, ApiProfile> loadProfiles(ConfigurationSection section) {
    ConfigurationSection defaultSection = section == null ? null : section.getConfigurationSection(DEFAULT_PROFILE);
    ApiProfile base = readProfile(DEFAULT_PROFILE, defaultSection,
        new ApiProfile(DEFAULT_PROFILE, ProviderSlot.PRIMARY, null, null, null, null, true));

    Map<String, ApiProfile> out = new LinkedHashMap<>();
    out.put(DEFAULT_PROFILE, base);
    if (section == null) return out;
    for (String name : section.getKeys(false)) {
      if (name.equalsIgnoreCase(DEFAULT_PROFILE)) continue;
      ConfigurationSection profile = section.getConfigurationSection(name);
      if (profile == null) continue;
      out.put(name.toLowerCase(Locale.ROOT), readProfile(name, profile, base));
    }
    return out;
  }

  /** Reads one profile; missing fields come from {@code inherit}. */
  private static ApiProfile readProfile(String name, ConfigurationSection s, ApiProfile inherit) {
    if (s == null) {
      return new ApiProfile(name, inherit.slot(), inherit.model(), inherit.maxOutputTokens(),
          inherit.temperature(), inherit.timeoutMs(), inherit.allowFallback());
    }
    ProviderSlot slot = inherit.slot();
    if (s.isSet("provider")) {
      String value = s.getString("provider", "primary");
      slot = "fallback".equalsIgnoreCase(value == null ? "" : value.trim()) ? ProviderSlot.FALLBACK : ProviderSlot.PRIMARY;
    }
    String model = inherit.model();
    if (s.isSet("model")) {
      String value = s.getString("model", "");
      model = value == null || value.isBlank() ? null : value.trim();
    }
    Long maxOutput = s.isSet("max-output-tokens")
        ? Long.valueOf(Math.max(s.getLong("max-output-tokens"), 1L)) : inherit.maxOutputTokens();
    Double temperature = s.isSet("temperature")
        ? Double.valueOf(Math.max(0.0D, Math.min(2.0D, s.getDouble("temperature")))) : inherit.temperature();
    Long timeout = s.isSet("request-timeout-ms")
        ? Long.valueOf(Math.max(s.getLong("request-timeout-ms"), CompletionRequest.MIN_TIMEOUT_MS)) : inherit.timeoutMs();
    boolean allowFallback = s.isSet("allow-fallback") ? s.getBoolean("allow-fallback") : inherit.allowFallback();
    return new ApiProfile(name, slot, model, maxOutput, temperature, timeout, allowFallback);
  }

  private static Map<String, ApiClientPolicy> loadClients(ConfigurationSection section) {
    Map<String, ApiClientPolicy> out = new LinkedHashMap<>();
    if (section == null) return out;
    for (String name : section.getKeys(false)) {
      ConfigurationSection c = section.getConfigurationSection(name);
      if (c == null) continue;
      List<String> profiles = new ArrayList<>();
      for (String profile : c.getStringList("profiles")) {
        if (profile != null && !profile.isBlank()) profiles.add(profile.trim());
      }
      out.put(name.toLowerCase(Locale.ROOT), new ApiClientPolicy(
          name,
          c.getBoolean("enabled", true),
          profiles,
          Math.max(c.getInt("max-requests-per-minute", 0), 0),
          Math.max(c.getInt("max-requests-per-player-per-minute", 0), 0),
          Math.max(c.getLong("daily-token-budget", 0L), 0L)));
    }
    return out;
  }

  public boolean enabled() {
    return enabled;
  }

  public int maxConcurrentRequests() {
    return maxConcurrentRequests;
  }

  public int maxRequestsPerMinute() {
    return maxRequestsPerMinute;
  }

  public int reserveForAssistantRpm() {
    return reserveForAssistantRpm;
  }

  public long maxQueueWaitMs() {
    return maxQueueWaitMs;
  }

  public int maxQueueSize() {
    return maxQueueSize;
  }

  public boolean allowUnknownClients() {
    return allowUnknownClients;
  }

  public boolean logRequests() {
    return logRequests;
  }

  public int maxMessages() {
    return maxMessages;
  }

  public int maxInputChars() {
    return maxInputChars;
  }

  /** Empty = no base system message is added. */
  public String baseSystemPrompt() {
    return baseSystemPrompt;
  }

  /** Resolved profile, or null if it does not exist. */
  public ApiProfile profile(String name) {
    if (name == null || name.isBlank()) return profiles.get(DEFAULT_PROFILE);
    return profiles.get(name.trim().toLowerCase(Locale.ROOT));
  }

  public List<String> profileNames() {
    List<String> names = new ArrayList<>();
    for (ApiProfile profile : profiles.values()) names.add(profile.name());
    return names;
  }

  /** Configured policy for this plugin name, or null if it is not listed. */
  public ApiClientPolicy configuredClient(String pluginName) {
    return pluginName == null ? null : clients.get(pluginName.toLowerCase(Locale.ROOT));
  }

  /**
   * Policy that applies to this plugin, or null if it may not call the API: listed
   * and enabled, or not listed while unknown-clients is allow.
   */
  public ApiClientPolicy effectiveClient(String pluginName) {
    ApiClientPolicy configured = configuredClient(pluginName);
    if (configured != null) return configured.enabled() ? configured : null;
    return allowUnknownClients ? ApiClientPolicy.unknown(pluginName) : null;
  }

  public List<ApiClientPolicy> clients() {
    return List.copyOf(clients.values());
  }
}
