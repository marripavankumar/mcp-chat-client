package com.restmcp.chat.web;

import java.util.Arrays;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.openai.errors.OpenAIServiceException;
import reactor.core.publisher.Flux;
import com.restmcp.chat.config.ChatProperties;
import com.restmcp.chat.models.OpenRouterModelService;
import com.restmcp.chat.models.OpenRouterModelService.ModelInfo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final OpenRouterModelService modelService;
    private final ChatProperties properties;
    private final List<ToolCallbackProvider> toolCallbackProviders;

    public ChatController(ChatClient chatClient, ChatMemory chatMemory, OpenRouterModelService modelService,
            ChatProperties properties, List<ToolCallbackProvider> toolCallbackProviders) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.modelService = modelService;
        this.properties = properties;
        this.toolCallbackProviders = toolCallbackProviders;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String model = resolveModel(request);
        String reply = prompt(request, model).call().content();
        return new ChatResponse(model, reply);
    }

    /**
     * Streams the reply as Server-Sent Events: {@code model} once, {@code delta} per text chunk, then {@code done}
     * or {@code error}. Chunks are JSON so newlines and leading spaces survive SSE framing.
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamChunk>> chatStream(@Valid @RequestBody ChatRequest request) {
        String model = resolveModel(request);
        return Flux.concat(
                Flux.just(event("model", model)),
                prompt(request, model).stream().content().map(text -> event("delta", text)),
                Flux.just(event("done", "")))
                .onErrorResume(ex -> Flux.just(event("error", errorMessage(ex))));
    }

    private String resolveModel(ChatRequest request) {
        String model = StringUtils.hasText(request.model()) ? request.model() : properties.defaultModel();
        if (!modelService.isSelectable(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Model '" + model + "' is not an OpenRouter model with tool-calling support");
        }
        return model;
    }

    private ChatClient.ChatClientRequestSpec prompt(ChatRequest request, String model) {
        return chatClient.prompt()
                .user(request.message())
                .options(OpenAiChatOptions.builder().model(model))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()));
    }

    private static ServerSentEvent<StreamChunk> event(String type, String text) {
        return ServerSentEvent.builder(new StreamChunk(text)).event(type).build();
    }

    private static String errorMessage(Throwable ex) {
        if (ex instanceof OpenAIServiceException openAi) {
            return "OpenRouter returned " + openAi.statusCode() + ": " + openAi.getMessage();
        }
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    @DeleteMapping("/chat/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
    }

    @GetMapping("/models")
    public ModelsResponse models() {
        return new ModelsResponse(properties.defaultModel(), modelService.listModels());
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return toolCallbackProviders.stream()
                .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                .map(t -> new ToolInfo(t.getToolDefinition().name(), t.getToolDefinition().description()))
                .toList();
    }

    /** Surfaces OpenRouter failures (bad key, no credits, model rejected tools...) to the UI instead of a bare 500. */
    @ExceptionHandler(OpenAIServiceException.class)
    public ProblemDetail openRouterError(OpenAIServiceException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "OpenRouter returned " + ex.statusCode() + ": " + ex.getMessage());
    }

    public record ChatRequest(@NotBlank @Size(max = 100) String conversationId,
            @NotBlank @Size(max = 8000) String message, String model) {
    }

    public record ChatResponse(String model, String reply) {
    }

    public record StreamChunk(String text) {
    }

    public record ModelsResponse(String defaultModel, List<ModelInfo> models) {
    }

    public record ToolInfo(String name, String description) {
    }
}
