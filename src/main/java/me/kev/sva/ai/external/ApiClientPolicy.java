package me.kev.sva.ai.external;

import java.util.List;

/**
 * One api.clients entry. For every limit, 0 means "no limit".
 *
 * @param profiles allowed profile names; empty = only "default"
 */
public record ApiClientPolicy(
    String name,
    boolean enabled,
    List<String> profiles,
    int maxRequestsPerMinute,
    int maxRequestsPerPlayerPerMinute,
    long dailyTokenBudget) {

  public ApiClientPolicy {
    profiles = List.copyOf(profiles);
  }

  /** Permissive policy for clients admitted through unknown-clients: allow (default profile only). */
  public static ApiClientPolicy unknown(String name) {
    return new ApiClientPolicy(name, true, List.of(), 0, 0, 0L);
  }

  public boolean allowsProfile(String profile) {
    if (profiles.isEmpty()) return ApiSettings.DEFAULT_PROFILE.equalsIgnoreCase(profile);
    for (String allowed : profiles) {
      if (allowed.equalsIgnoreCase(profile)) return true;
    }
    return false;
  }
}
