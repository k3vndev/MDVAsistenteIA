package me.kev.sva.ai.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import me.kev.sva.ai.ProviderSlot;

class ApiSettingsTest {

  private static ApiSettings load(String yaml) {
    return ApiSettings.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
  }

  private static final String YAML = """
      api:
        enabled: true
        max-concurrent-requests: 3
        unknown-clients: deny
        profiles:
          default:
            provider: fallback
            max-output-tokens: 300
            temperature: 0.7
          short:
            max-output-tokens: 50
            request-timeout-ms: 100
          custom:
            provider: primary
            model: gpt-4o
            allow-fallback: false
        clients:
          MDVTrivia:
            profiles: [short, default]
            max-requests-per-minute: 3
          Disabled:
            enabled: false
          NoProfiles:
            max-requests-per-minute: 0
      """;

  @Test
  void profilesInheritFromDefaultThenProvider() {
    ApiSettings settings = load(YAML);
    ApiProfile base = settings.profile("default");
    assertEquals(ProviderSlot.FALLBACK, base.slot());
    assertNull(base.model());
    assertNull(base.timeoutMs());
    assertTrue(base.allowFallback());

    ApiProfile shortProfile = settings.profile("SHORT");
    assertEquals(ProviderSlot.FALLBACK, shortProfile.slot());
    assertEquals(50L, shortProfile.maxOutputTokens());
    assertEquals(0.7, shortProfile.temperature());
    assertEquals(3000L, shortProfile.timeoutMs()); // never below 3000

    ApiProfile custom = settings.profile("custom");
    assertEquals(ProviderSlot.PRIMARY, custom.slot());
    assertEquals("gpt-4o", custom.model());
    assertEquals(300L, custom.maxOutputTokens());
    assertFalse(custom.allowFallback());

    assertNull(settings.profile("missing"));
    assertEquals(settings.profile("default"), settings.profile(null));
    assertEquals(3, settings.maxConcurrentRequests());
  }

  @Test
  void clientPermissions() {
    ApiSettings settings = load(YAML);
    ApiClientPolicy trivia = settings.effectiveClient("mdvtrivia");
    assertNotNull(trivia);
    assertTrue(trivia.allowsProfile("short"));
    assertTrue(trivia.allowsProfile("DEFAULT"));
    assertFalse(trivia.allowsProfile("custom"));
    assertEquals(3, trivia.maxRequestsPerMinute());

    assertNull(settings.effectiveClient("Disabled"));
    assertNull(settings.effectiveClient("Unknown"));

    ApiClientPolicy noProfiles = settings.effectiveClient("NoProfiles");
    assertTrue(noProfiles.allowsProfile("default"));
    assertFalse(noProfiles.allowsProfile("short"));
  }

  @Test
  void unknownClientsAllowGetsOnlyDefault() {
    ApiSettings settings = load(YAML.replace("unknown-clients: deny", "unknown-clients: allow"));
    ApiClientPolicy unknown = settings.effectiveClient("SomePlugin");
    assertNotNull(unknown);
    assertTrue(unknown.allowsProfile("default"));
    assertFalse(unknown.allowsProfile("short"));
    assertNull(settings.effectiveClient("Disabled"));
  }

  @Test
  void missingSectionUsesSafeDefaults() {
    ApiSettings settings = load("other: 1\n");
    assertTrue(settings.enabled());
    assertFalse(settings.allowUnknownClients());
    assertEquals(20, settings.maxMessages());
    assertEquals(12_000, settings.maxInputChars());
    assertEquals(ApiSettings.DEFAULT_BASE_SYSTEM_PROMPT, settings.baseSystemPrompt());
    ApiProfile base = settings.profile("default");
    assertEquals(ProviderSlot.PRIMARY, base.slot());
    assertNull(base.maxOutputTokens());
  }

  @Test
  void bundledConfigLoads() throws Exception {
    try (var stream = ApiSettingsTest.class.getResourceAsStream("/config.yml")) {
      assertNotNull(stream);
      ApiSettings settings = ApiSettings.load(YamlConfiguration.loadConfiguration(
          new InputStreamReader(stream, StandardCharsets.UTF_8)));
      assertTrue(settings.enabled());
      assertEquals(1200L, settings.profile("trivia-generate").maxOutputTokens());
      assertEquals(0.6, settings.profile("tutorial-hint").temperature());
      assertEquals(12000L, settings.profile("tutorial-hint").timeoutMs());
      assertTrue(settings.effectiveClient("MDVTutorial").allowsProfile("tutorial-hint"));
      assertEquals(2, settings.effectiveClient("MDVTutorial").maxRequestsPerPlayerPerMinute());
      assertNull(settings.effectiveClient("RandomPlugin"));
    }
  }
}
