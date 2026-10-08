# MCP Chat Client

A Spring AI chatbot that answers questions about employees and departments by calling the
tools on the [RestAPIMCPDemo](../RestAPIMCPDemo) MCP server. The LLM is any
tool-capable model on [OpenRouter](https://openrouter.ai), picked from a dropdown in the UI.

```
Browser ──► mcp-chat-client :8081 ──► OpenRouter (LLM)
                     │
                     └── MCP streamable HTTP ──► RestAPIMCPDemo :8080/mcp
```

Stack: Spring Boot 4.1.1, Spring AI 2.0.1 (`spring-ai-starter-model-openai` pointed at
OpenRouter's OpenAI-compatible API, `spring-ai-starter-mcp-client`), Java 21.

## Run

1. Start the MCP server (from `RestAPIMCPDemo`): `./mvnw spring-boot:run`
2. Start the chatbot:

   ```powershell
   $env:OPENROUTER_API_KEY = "sk-or-..."
   ./mvnw spring-boot:run
   ```

3. Open http://localhost:8081, pick a model, and ask e.g. *"Which department has the most employees?"*

The MCP server must be running first: the client connects and loads its tools at startup.

## Configuration

| Environment variable       | Default                      | Purpose                                                   |
|----------------------------|------------------------------|-----------------------------------------------------------|
| `OPENROUTER_API_KEY`       | (required)                   | OpenRouter API key                                        |
| `OPENROUTER_DEFAULT_MODEL` | `anthropic/claude-opus-5.5`  | Model pre-selected in the UI / used when none is sent     |
| `MCP_SERVER_URL`           | `http://localhost:8080`      | Base URL of the MCP server (endpoint `/mcp`)              |
| `MCP_API_KEY`              | (empty)                      | Sent as `X-API-KEY`; must match the server's `APP_API_KEY` |
| `SERVER_PORT`              | `8081`                       | Port of this app                                          |

## Model list

`GET /api/models` fetches `https://openrouter.ai/api/v1/models?supported_parameters=tools`
(cached for an hour), so only models that can call tools are offered; `:batch` variants are
hidden. The dropdown shows USD per million input/output tokens. Requests naming a model outside
that list are rejected with 400.

## API

| Method | Path                       | Body / result                                                       |
|--------|----------------------------|---------------------------------------------------------------------|
| POST   | `/api/chat`                | `{"conversationId","message","model"?}` → `{"model","reply"}`       |
| DELETE | `/api/chat/{conversationId}` | Clears that conversation's memory                                 |
| GET    | `/api/models`              | `{"defaultModel","models":[{id,name,contextLength,inputPerMillion,outputPerMillion}]}` |
| GET    | `/api/tools`               | Tools discovered on the MCP server                                  |

Conversation history is kept in memory (last 20 messages per conversation) and is lost on restart.
The app binds to `127.0.0.1` because the chat API has no authentication and spends your
OpenRouter credits.
