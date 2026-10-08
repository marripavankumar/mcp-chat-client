package com.restmcp.chat.config;

import java.util.List;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class ChatConfig {

    private static final String SYSTEM_PROMPT = """
            You are an HR assistant for an organisation. Answer questions about employees and
            departments using the available tools; never invent ids, names or figures.
            Look up ids with list_departments or search_employees before calling tools that need an id.
            Before calling any delete tool or transfer_employee, describe exactly what will change
            and wait for the user to confirm in their next message.
            Keep answers short and use markdown tables for lists of records.
            """;

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
            List<ToolCallbackProvider> toolCallbackProviders) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolCallbacks(toolCallbackProviders.toArray(ToolCallbackProvider[]::new))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** Sends the MCP server's API key on every MCP request when one is configured. */
    @Bean
    McpSyncHttpClientRequestCustomizer mcpApiKeyCustomizer(ChatProperties properties) {
        String apiKey = properties.mcpApiKey();
        return (request, method, uri, body, context) -> {
            if (StringUtils.hasText(apiKey)) {
                request.header("X-API-KEY", apiKey);
            }
        };
    }
}
