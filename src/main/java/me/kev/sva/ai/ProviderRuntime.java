package me.kev.sva.ai;

import com.openai.client.OpenAIClient;

/** Settings plus the HTTP client built from them (client is null when unconfigured). */
record ProviderRuntime(ProviderSettings settings, OpenAIClient client) {
}
