package me.kev.sva.ai.external;

import me.kev.sva.ai.ProviderSlot;

/**
 * One resolved api.profiles entry. Null fields mean "use the provider's value".
 *
 * @param model           null = the provider's model
 * @param maxOutputTokens null = the provider's limit
 * @param temperature     null = the provider's temperature
 * @param timeoutMs       null = the provider's timeout (never below 3000 when set)
 */
public record ApiProfile(
    String name,
    ProviderSlot slot,
    String model,
    Long maxOutputTokens,
    Double temperature,
    Long timeoutMs,
    boolean allowFallback) {
}
