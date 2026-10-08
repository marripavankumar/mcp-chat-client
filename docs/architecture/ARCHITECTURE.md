# MCP Chat Client — Architecture

| Item | Value |
|---|---|
| Status | Implemented and verified end-to-end against the live MCP server and OpenRouter |
| Date | 2026-10-08 |
| Stack | Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (`spring-ai-starter-model-openai` on `openai-java` 4.49, `spring-ai-starter-mcp-client` on MCP Java SDK 2.0), Reactor, vanilla HTML/JS UI (marked + DOMPurify) |
| LLM gateway | [OpenRouter](https://openrouter.ai) (OpenAI-compatible API, ~300 tool-capable models) |
| MCP server | [RestAPIMCPDemo](https://github.com/marripavankumar/RestAPIMCPDemo): its own architecture is in that repo's `docs/architecture/ARCHITECTURE.md` |

---

## 1. Purpose and scope

The chat client is a **web chatbot** that lets a business user ask questions about employees and departments in plain
language. It does not hold any HR data or business logic itself. It:

1. sends the conversation to an **LLM chosen by the user** (any OpenRouter model that supports tool calling), and
2. lets that LLM call the **MCP tools** published by the Employee & Department MCP server, then streams the answer back.

### In scope
- Chat UI with streamed replies, markdown rendering, conversation reset
- Model picker over the live OpenRouter catalogue (search, provider filter, sort, price and context badges)
- MCP client over Streamable HTTP with optional API key
- Per-conversation memory, server-side model validation, error reporting to the UI

### Out of scope
- User accounts and authentication, persistent chat history, MCP resources and prompts (tools only), multi-server routing

---

## 2. System context

```mermaid
flowchart LR
    USER["Business user<br/>web browser"]

    subgraph Local["User's machine (localhost)"]
        CHAT["MCP Chat Client<br/>Spring Boot :8081"]
        MCPS["Employee & Department Service<br/>REST API + MCP server :8080"]
        DB[("H2 / PostgreSQL")]
    end

    subgraph Internet
        OR["OpenRouter<br/>openrouter.ai/api/v1"]
        PROV["Model providers<br/>Anthropic, OpenAI, Google,<br/>Meta, Mistral, Qwen, xAI, ..."]
    end

    OTHER["Other MCP hosts<br/>Claude Code, Claude Desktop, VS Code"]

    USER -- "HTTP: page, JSON, SSE stream" --> CHAT
    CHAT -- "HTTPS: chat completions (streaming)<br/>+ model catalogue" --> OR
    OR --> PROV
    CHAT -- "MCP JSON-RPC<br/>Streamable HTTP POST /mcp" --> MCPS
    OTHER -- "MCP" --> MCPS
    MCPS -- "JDBC" --> DB
```

| Actor / system | Role |
|---|---|
| Business user | Chats in the browser and picks the model |
| MCP Chat Client | MCP **host + client**: owns the conversation, the tool-calling loop and the UI |
| OpenRouter | One API key and one OpenAI-compatible endpoint in front of many model providers |
| Employee & Department MCP server | Publishes 14 tools; the only component that touches the database |

The LLM never talks to the MCP server. It only returns *tool call requests*; the chat client executes them through its
MCP client and sends the results back to the LLM.

---

## 3. Key architectural decisions

| # | Decision | Chosen option | Alternatives considered | Rationale |
|---|---|---|---|---|
| ADR-01 | Separate deployable | **Own repo and JVM**, talks to the server only through MCP | Embed chat in the MCP server | Keeps the server a reusable MCP endpoint for any host; the client can point at any MCP server by URL. |
| ADR-02 | LLM access | **OpenRouter via Spring AI's OpenAI starter** (`base-url: https://openrouter.ai/api/v1`) | One provider SDK (e.g. Anthropic only) | One key, hundreds of models, user can switch per message. OpenRouter speaks the OpenAI Chat Completions protocol, so no custom code is needed. |
| ADR-03 | Orchestration | **Spring AI `ChatClient`** with advisors and tool callbacks | Hand-written tool-call loop | Spring AI runs the loop: send tools → receive `tool_calls` → execute → resend, in both blocking and streaming modes. |
| ADR-04 | MCP transport | **Streamable HTTP, SYNC client** | SSE, STDIO, ASYNC client | Matches the server's default transport; sync matches the server's blocking JPA design. |
| ADR-05 | Tool exposure | **All MCP tools on every request** through `SyncMcpToolCallbackProvider` | Hand-picked tool subset | 14 tools is small; the model chooses. Tool discovery happens once at startup. |
| ADR-06 | Model catalogue | **Live OpenRouter list filtered by `supported_parameters=tools`**, cached 1 h, `:batch` variants removed | Hard-coded list | Always current; models without tool support would fail as soon as MCP tools are attached. |
| ADR-07 | Model choice | **Per request** via `OpenAiChatOptions.model(...)`, validated against the catalogue | Fixed in config | Users can compare models mid-conversation; validation stops arbitrary ids. |
| ADR-08 | Streaming | **Server-Sent Events** from `Flux<ServerSentEvent<StreamChunk>>`, each chunk JSON-encoded | WebSocket, raw text stream | One-way stream fits; JSON keeps newlines and leading spaces intact; typed events (`model`, `delta`, `done`, `error`). |
| ADR-09 | Memory | **`MessageWindowChatMemory` (last 20 messages) in memory**, keyed by `conversationId` | JDBC chat memory | Demo scope; swap the repository bean for persistence. |
| ADR-10 | UI | **Single static `index.html`**, no build step | React/Vite SPA | Zero tooling; served by Spring Boot; LLM output sanitised with DOMPurify. |
| ADR-11 | Exposure | **Bind to 127.0.0.1**, no auth | Login | The API spends the owner's OpenRouter credits; local-only until auth is added. |

---

## 4. Container / deployment view

```mermaid
flowchart TB
    subgraph Browser["Browser"]
        UI["index.html<br/>chat + model picker"]
    end

    subgraph ClientJVM["mcp-chat-client JVM — 127.0.0.1:8081"]
        TOM1["Embedded Tomcat"]
        API["REST + SSE endpoints<br/>/api/chat, /api/chat/stream,<br/>/api/models, /api/tools"]
        CC["Spring AI ChatClient"]
        MEM["ChatMemory<br/>(in-memory)"]
        MCPC["McpSyncClient<br/>HttpClientStreamableHttpTransport"]
        OAI["OpenAiChatModel<br/>(openai-java SDK)"]
        CAT["OpenRouterModelService<br/>(RestClient + 1 h cache)"]
    end

    subgraph ServerJVM["rest-api-mcp-demo JVM — 127.0.0.1:8080"]
        MCPEP["/mcp Streamable HTTP"]
        SVC["Service layer"]
    end

    DB[("H2 / PostgreSQL")]
    OR["OpenRouter API"]

    UI -- "GET /, fetch() JSON, SSE" --> TOM1 --> API
    API --> CC
    API --> CAT
    CC --> MEM
    CC --> OAI
    CC --> MCPC
    OAI -- "HTTPS + Bearer OPENROUTER_API_KEY" --> OR
    CAT -- "HTTPS GET /models?supported_parameters=tools" --> OR
    MCPC -- "HTTP POST /mcp<br/>X-API-KEY (optional)" --> MCPEP --> SVC --> DB
```

| Process | Port | Start command | Depends on |
|---|---|---|---|
| MCP server (`RestAPIMCPDemo`) | 8080 | `./mvnw spring-boot:run` | Database (H2 in-memory by default) |
| Chat client (`mcp-chat-client`) | 8081 | `OPENROUTER_API_KEY=... ./mvnw spring-boot:run` | MCP server **must be up first** (tools are loaded at startup), OpenRouter |

---

## 5. Component view

```mermaid
flowchart TB
    subgraph Web["Web layer — com.restmcp.chat.web"]
        CTRL["ChatController<br/>POST /api/chat · POST /api/chat/stream<br/>DELETE /api/chat/{id} · GET /api/models · GET /api/tools<br/>@ExceptionHandler OpenAIServiceException → 502"]
    end

    subgraph Config["Configuration — com.restmcp.chat.config"]
        CFG["ChatConfig<br/>ChatClient bean: system prompt,<br/>MCP tool callbacks, memory advisor"]
        CUST["McpSyncHttpClientRequestCustomizer<br/>adds X-API-KEY"]
        PROPS["ChatProperties<br/>app.chat.*"]
    end

    subgraph Models["Model catalogue — com.restmcp.chat.models"]
        MS["OpenRouterModelService<br/>fetch, filter, price per 1M, cache"]
    end

    subgraph SpringAI["Spring AI (auto-configured)"]
        CHC["ChatClient"]
        ADV["MessageChatMemoryAdvisor"]
        MEMW["MessageWindowChatMemory<br/>InMemoryChatMemoryRepository"]
        TCM["ToolCallingManager<br/>(internal tool execution loop)"]
        OCM["OpenAiChatModel"]
        TCP["SyncMcpToolCallbackProvider<br/>14 x SyncMcpToolCallback"]
    end

    subgraph MCPSDK["MCP Java SDK 2.0"]
        SYNC["McpSyncClient 'emp-dept'"]
        TRANS["HttpClientStreamableHttpTransport"]
    end

    CTRL --> CHC
    CTRL --> MS
    CTRL --> TCP
    CTRL --> MEMW
    CFG --> CHC
    PROPS --> CFG
    PROPS --> MS
    CHC --> ADV --> MEMW
    CHC --> OCM
    OCM --> TCM --> TCP --> SYNC --> TRANS
    CUST --> TRANS
```

**Rule:** the client contains no HR business rules. Everything about employees and departments, including validation
and confirmation-worthy operations, lives in the MCP server. The client adds only conversation state, model choice and
presentation.

### 5.1 Source layout

```
mcp-chat-client/
├── pom.xml                                   Boot 4.1.1, Spring AI BOM 2.0.1
├── src/main/java/com/restmcp/chat/
│   ├── McpChatClientApplication.java         @SpringBootApplication + @ConfigurationPropertiesScan
│   ├── config/
│   │   ├── ChatConfig.java                   ChatClient bean, system prompt, MCP API-key customizer
│   │   └── ChatProperties.java               app.chat.* (default model, catalogue URL, cache TTL, MCP key)
│   ├── models/
│   │   └── OpenRouterModelService.java       catalogue fetch / filter / cache, model validation
│   └── web/
│       └── ChatController.java               REST + SSE endpoints, error mapping
├── src/main/resources/
│   ├── application.yml                       OpenRouter, MCP connection, timeouts
│   └── static/index.html                     chat UI + model picker
└── docs/architecture/                        this document (Markdown + PDF)
```

---

## 6. Class model

```mermaid
classDiagram
    direction LR

    class ChatController {
        -ChatClient chatClient
        -ChatMemory chatMemory
        -OpenRouterModelService modelService
        -ChatProperties properties
        -List~ToolCallbackProvider~ toolCallbackProviders
        +chat(ChatRequest) ChatResponse
        +chatStream(ChatRequest) Flux~ServerSentEvent~
        +clear(String conversationId)
        +models() ModelsResponse
        +tools() List~ToolInfo~
        +openRouterError(OpenAIServiceException) ProblemDetail
        -resolveModel(ChatRequest) String
        -prompt(ChatRequest, String) ChatClientRequestSpec
    }

    class ChatConfig {
        +chatClient(Builder, ChatMemory, List~ToolCallbackProvider~) ChatClient
        +mcpApiKeyCustomizer(ChatProperties) McpSyncHttpClientRequestCustomizer
    }

    class ChatProperties {
        <<record>>
        String defaultModel
        String modelsUrl
        Duration modelsCacheTtl
        String mcpApiKey
    }

    class OpenRouterModelService {
        -RestClient restClient
        -List~ModelInfo~ cached
        -Instant fetchedAt
        +listModels() List~ModelInfo~
        +isSelectable(String) boolean
        -refresh()
    }

    class ModelInfo {
        <<record>>
        String id
        String name
        String description
        Long created
        Long contextLength
        Double inputPerMillion
        Double outputPerMillion
    }

    class ChatRequest {
        <<record>>
        String conversationId
        String message
        String model
    }

    class StreamChunk {
        <<record>>
        String text
    }

    ChatController --> OpenRouterModelService
    ChatController --> ChatProperties
    ChatController ..> ChatRequest
    ChatController ..> StreamChunk
    ChatConfig ..> ChatProperties
    OpenRouterModelService --> ChatProperties
    OpenRouterModelService o-- ModelInfo
```

---

## 7. Runtime behaviour

### 7.1 Startup: connecting to the MCP server

```mermaid
sequenceDiagram
    autonumber
    participant AC as MCP client<br/>auto-config
    participant S as MCP server<br/>/mcp
    participant P as ToolCallback<br/>Provider
    participant CFG as ChatConfig

    Note over AC: connections.emp-dept<br/>HttpClientStreamableHttpTransport<br/>+ X-API-KEY customizer
    AC->>S: initialize (mcp-chat-client 1.0.0)
    S-->>AC: serverInfo, capabilities,<br/>instructions, Mcp-Session-Id
    AC->>S: notifications/initialized
    AC->>P: register McpSyncClient
    P->>S: tools/list
    S-->>P: 14 tools (name, description, schema)
    P-->>CFG: ToolCallbackProvider bean
    Note over CFG: ChatClient.builder()<br/>defaultSystem(prompt)<br/>defaultToolCallbacks(provider)<br/>defaultAdvisors(memory)
```

If the MCP server is down, `initialize` fails and the client does not start (`spring.ai.mcp.client.initialized=true`).

### 7.2 Streaming chat with tool calls

```mermaid
sequenceDiagram
    autonumber
    participant UI as Browser<br/>index.html
    participant C as ChatController
    participant CC as ChatClient<br/>+ memory
    participant OR as OpenRouter<br/>+ model
    participant S as MCP server

    UI->>C: POST /api/chat/stream<br/>{conversationId, message, model}
    Note over C: model in catalogue?<br/>no → 400 ProblemDetail
    C-->>UI: event: model
    C->>CC: prompt(...).stream().content()
    Note over CC: load last 20 messages
    CC->>OR: system + history + message<br/>+ 14 tool schemas (stream)
    OR-->>CC: tool_calls: list_departments {}
    CC->>S: tools/call list_departments
    S-->>CC: JSON result
    CC->>OR: history + tool result (stream)
    loop until no more tool calls
        OR-->>CC: tool_calls or text deltas
    end
    OR-->>CC: text deltas
    CC-->>C: Flux of String
    C-->>UI: event: delta (many)
    Note over CC: save message + reply
    C-->>UI: event: done
    Note over UI: marked → DOMPurify<br/>render as chunks arrive
```

Inside "ChatClient + memory": `MessageChatMemoryAdvisor` loads and saves history, `OpenAiChatModel` calls OpenRouter,
`ToolCallingManager` runs the tool loop, and each call goes through a `SyncMcpToolCallback` → `McpSyncClient` →
`HttpClientStreamableHttpTransport`. Tool calls and tool results are exchanged between the client and the model only;
the browser sees the final text.

### 7.3 Model catalogue

```mermaid
sequenceDiagram
    autonumber
    participant UI as index.html
    participant C as ChatController
    participant MS as OpenRouterModelService
    participant OR as OpenRouter

    UI->>C: GET /api/models
    C->>MS: listModels()
    alt cache empty or older than 1 h
        MS->>OR: GET /api/v1/models?supported_parameters=tools
        OR-->>MS: {data: [{id, name, description, created,<br/>context_length, pricing, supported_parameters}]}
        MS->>MS: drop ":batch" ids, keep tools-capable,<br/>USD/token → USD per 1M, sort by id
    end
    alt fetch failed and nothing cached
        MS-->>C: [default model only]
    else
        MS-->>C: cached list (~300 models)
    end
    C-->>UI: {defaultModel, models}
    UI->>UI: choose saved model → default → first
```

### 7.4 SSE stream format

```text
event:model
data:{"text":"anthropic/claude-opus-5.5"}

event:delta
data:{"text":"**Engineering** has the most"}

event:delta
data:{"text":" employees, with **4** people."}

event:done
data:{"text":""}
```

| Event | When | UI action |
|---|---|---|
| `model` | First, before the LLM is called | Label the reply bubble with the model name |
| `delta` | Each text chunk from the model | Append, re-render markdown |
| `done` | Stream completed | Remove the typing cursor |
| `error` | Upstream failure after the stream started | Show a red error bubble |

### 7.5 Error handling

```mermaid
flowchart TD
    A["Request"] --> B{"Valid body?<br/>conversationId, message"}
    B -- no --> E400["400 ProblemDetail<br/>(Bean Validation)"]
    B -- yes --> C{"Model in tool-capable<br/>catalogue?"}
    C -- no --> E400b["400 ProblemDetail<br/>not a tool-capable model"]
    C -- yes --> D["Call LLM"]
    D --> F{"Failure?"}
    F -- "none" --> OK["reply / SSE stream"]
    F -- "OpenRouter 4xx/5xx<br/>(bad key, no credits, model error)" --> G{"Endpoint"}
    G -- "/api/chat" --> E502["502 ProblemDetail<br/>OpenRouter returned 401: ..."]
    G -- "/api/chat/stream" --> ESSE["event: error<br/>same message"]
    F -- "MCP tool error" --> TOOL["Tool result isError=true<br/>goes back to the model,<br/>which explains or retries"]
```

---

## 8. API

| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/chat` | `{"conversationId","message","model"?}` | `{"model","reply"}` |
| POST | `/api/chat/stream` | same | `text/event-stream` (§7.4) |
| DELETE | `/api/chat/{conversationId}` | — | 204, memory cleared |
| GET | `/api/models` | — | `{"defaultModel","models":[ModelInfo]}` |
| GET | `/api/tools` | — | `[{"name","description"}]` from the MCP server |
| GET | `/` | — | Chat UI |

Limits: `conversationId` ≤ 100 chars, `message` ≤ 8000 chars. Omitting `model` uses `app.chat.default-model`.

---

## 9. User interface

```mermaid
flowchart LR
    subgraph Page["index.html"]
        SIDE["Sidebar<br/>New conversation · MCP status · tool list"]
        TOP["Header<br/>model trigger (Ctrl+K)"]
        POP["Picker popover<br/>search · provider chips · sort ·<br/>free only · grouped rows"]
        LOG["Message log<br/>welcome + suggestions,<br/>user / bot / error bubbles"]
        COMP["Composer<br/>auto-growing textarea"]
    end

    TOP --> POP
    POP -- "choose → localStorage" --> TOP
    COMP -- "POST /api/chat/stream" --> LOG
    SIDE -- "GET /api/tools" --> SIDE
    TOP -- "GET /api/models" --> POP
```

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Sending : Enter / Send / suggestion
    Sending --> Streaming : event model
    Streaming --> Streaming : event delta
    Streaming --> Idle : event done
    Sending --> Failed : HTTP 4xx/5xx
    Streaming --> Failed : event error / network error
    Failed --> Idle : error bubble shown
```

| Picker feature | Detail |
|---|---|
| Search | Multi-word, matches id and display name |
| Provider chips | Built from the catalogue, ordered by model count |
| Sort | Provider (grouped, sticky headers) · Newest · Price low→high · Context largest |
| Badges | Context window (e.g. `1M`), USD per 1M input/output tokens, `Free` |
| Keyboard | Ctrl+K open, ↑/↓ move, Enter choose, Esc close |
| Persistence | Last choice in `localStorage` (wrapped in try/catch) |

LLM output is rendered with `marked` and sanitised with `DOMPurify`; all catalogue and tool text is inserted with
`textContent`.

---

## 10. Configuration

```yaml
server:
  port: ${SERVER_PORT:8081}
  address: ${SERVER_ADDRESS:127.0.0.1}
spring:
  mvc.async.request-timeout: 5m          # long streams with several tool calls
  ai:
    openai:
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY}
      chat.options.model: ${app.chat.default-model}
    mcp.client:
      type: SYNC
      request-timeout: 30s
      streamable-http.connections.emp-dept:
        url: ${MCP_SERVER_URL:http://localhost:8080}
        endpoint: /mcp
app.chat:
  default-model: ${OPENROUTER_DEFAULT_MODEL:anthropic/claude-opus-5.5}
  models-url: https://openrouter.ai/api/v1/models?supported_parameters=tools
  models-cache-ttl: 1h
  mcp-api-key: ${MCP_API_KEY:}
```

| Environment variable | Default | Purpose |
|---|---|---|
| `OPENROUTER_API_KEY` | required | OpenRouter key (never committed) |
| `OPENROUTER_DEFAULT_MODEL` | `anthropic/claude-opus-5.5` | Pre-selected model |
| `MCP_SERVER_URL` | `http://localhost:8080` | MCP server base URL |
| `MCP_API_KEY` | empty | Sent as `X-API-KEY`; must equal the server's `APP_API_KEY` |
| `SERVER_PORT` / `SERVER_ADDRESS` | `8081` / `127.0.0.1` | Bind address of the chat client |

---

## 11. Security

```mermaid
flowchart LR
    B["Browser on same machine"] -- "127.0.0.1:8081 only" --> CH["Chat client"]
    CH -- "Authorization: Bearer OPENROUTER_API_KEY<br/>(env var, never in files)" --> OR["OpenRouter"]
    CH -- "X-API-KEY = MCP_API_KEY<br/>(when the server requires it)" --> MS["MCP server"]
    MS -- "Origin check + API key filter" --> SVC["Services"]
```

| Concern | Control |
|---|---|
| Secret handling | Keys come from environment variables; `application.yml` only has placeholders |
| Unauthenticated chat API | Bound to `127.0.0.1`; add Spring Security before exposing it |
| Cost control | Only catalogue models accepted; prices shown before choosing |
| Prompt injection via tool results | Model output is sanitised before rendering; destructive tools require user confirmation per the system prompt and the server's tool descriptions |
| Destructive operations | System prompt: describe the change and wait for confirmation before `delete_*` or `transfer_employee` |
| XSS | DOMPurify on rendered markdown; `textContent` for all other dynamic text |
| MCP DNS rebinding | Java `HttpClient` sends no `Origin`, so the server's Origin filter allows it |

---

## 12. Cross-cutting concerns

| Concern | Approach |
|---|---|
| Timeouts | MCP request 30 s; MVC async 5 min for streams; OpenRouter SDK defaults |
| Memory | 20-message window per conversation, in-memory; lost on restart |
| Concurrency | Catalogue refresh is `synchronized`, list is `volatile`; ChatClient and MCP client are thread-safe |
| Observability | Logs only (catalogue fetch failures at WARN, upstream errors at ERROR). Add `spring-boot-starter-actuator` to export Spring AI's chat and tool observations as Micrometer metrics |
| Performance notes | First token waits for model thinking plus each tool round-trip; Opus-class models send larger chunks than Haiku-class |

---

## 13. Limitations and next steps

| Limitation | Possible next step |
|---|---|
| No authentication on the chat API | Spring Security (form login or OAuth2) and per-user conversations |
| Memory lost on restart | `JdbcChatMemoryRepository` |
| Client fails to start if the MCP server is down | Lazy MCP initialisation with retry, and a "reconnect" action |
| Tool calls invisible in the UI | Stream tool-call events (name, arguments, duration) as an extra SSE event type |
| No stop button | Cancel the `fetch` reader and dispose the server `Flux` |
| Tools only | Add MCP resources (`org://departments`) and prompts (`department_report`) to the UI |

---

## 14. Run book

```powershell
# 1. MCP server
cd RestAPIMCPDemo
./mvnw spring-boot:run

# 2. Chat client
cd mcp-chat-client
$env:OPENROUTER_API_KEY = "sk-or-..."
./mvnw spring-boot:run

# 3. Open http://localhost:8081
```

Smoke checks: `GET /api/tools` returns 14 tools; `GET /api/models` returns roughly 300 models; asking
"Which department has the most employees?" streams an answer naming Engineering (4 employees) with the seed data.
