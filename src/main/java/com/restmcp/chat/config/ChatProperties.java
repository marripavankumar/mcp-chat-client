package com.restmcp.chat.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultModel   OpenRouter model id pre-selected in the UI and used when a request names none
 * @param modelsUrl      OpenRouter endpoint listing models that support tool calling
 * @param modelsCacheTtl how long the fetched model list is reused
 * @param mcpApiKey      key for the MCP server's X-API-KEY check; empty when the server has auth disabled
 */
@ConfigurationProperties("app.chat")
public record ChatProperties(String defaultModel, String modelsUrl, Duration modelsCacheTtl, String mcpApiKey) {
}
