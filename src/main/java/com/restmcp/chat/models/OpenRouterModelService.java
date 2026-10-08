package com.restmcp.chat.models;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.restmcp.chat.config.ChatProperties;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Lists the OpenRouter models that support tool calling (the chatbot is useless without it).
 * The list is cached; if OpenRouter can't be reached the last good list, or just the default model, is returned.
 */
@Service
public class OpenRouterModelService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterModelService.class);

    private final RestClient restClient;
    private final ChatProperties properties;

    private volatile List<ModelInfo> cached = List.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public OpenRouterModelService(RestClient.Builder restClientBuilder, ChatProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public List<ModelInfo> listModels() {
        if (cached.isEmpty() || fetchedAt.plus(properties.modelsCacheTtl()).isBefore(Instant.now())) {
            refresh();
        }
        if (cached.isEmpty()) {
            return List.of(new ModelInfo(properties.defaultModel(), properties.defaultModel(), null, null, null,
                    null, null));
        }
        return cached;
    }

    /** True when the model is in the tool-capable list, or when the list couldn't be fetched. */
    public boolean isSelectable(String modelId) {
        List<ModelInfo> models = listModels();
        return cached.isEmpty() || models.stream().anyMatch(m -> m.id().equals(modelId));
    }

    private synchronized void refresh() {
        try {
            ModelsResponse response = restClient.get().uri(properties.modelsUrl()).retrieve().body(ModelsResponse.class);
            if (response == null || response.data() == null) {
                return;
            }
            cached = response.data().stream()
                    // ":batch" variants are for asynchronous batch jobs, not interactive chat
                    .filter(m -> m.id() != null && !m.id().endsWith(":batch"))
                    .filter(m -> m.supportedParameters() == null || m.supportedParameters().contains("tools"))
                    .map(OpenRouterModelService::toModelInfo)
                    .sorted(Comparator.comparing(ModelInfo::id))
                    .toList();
            fetchedAt = Instant.now();
        }
        catch (RestClientException ex) {
            log.warn("Could not fetch models from {}: {}", properties.modelsUrl(), ex.getMessage());
        }
    }

    private static ModelInfo toModelInfo(OpenRouterModel m) {
        Pricing pricing = m.pricing() == null ? new Pricing(null, null) : m.pricing();
        return new ModelInfo(m.id(), m.name() != null ? m.name() : m.id(), m.description(), m.created(),
                m.contextLength(), perMillion(pricing.prompt()), perMillion(pricing.completion()));
    }

    /** OpenRouter quotes USD per token; the UI shows USD per million tokens (rounded to hide float noise). */
    private static Double perMillion(String perToken) {
        try {
            return perToken == null ? null : Math.round(Double.parseDouble(perToken) * 1e10) / 1e4;
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    /** {@code created} is epoch seconds; prices are USD per million tokens. */
    public record ModelInfo(String id, String name, String description, Long created, Long contextLength,
            Double inputPerMillion, Double outputPerMillion) {
    }

    record ModelsResponse(List<OpenRouterModel> data) {
    }

    record OpenRouterModel(String id, String name, String description, Long created,
            @JsonProperty("context_length") Long contextLength,
            Pricing pricing, @JsonProperty("supported_parameters") List<String> supportedParameters) {
    }

    record Pricing(String prompt, String completion) {
    }
}
