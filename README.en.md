# Database Operation Agent

> **You no longer need developers or a separate system to analyze your data** — just chat in natural language and know your database data inside out; **your data, account credentials never leave your domain at any point** — not a single byte of database data is ever sent to the LLM!!!
>
> The agent only operates on the designated database(s); for any instruction or question other than database DDL / DML, it clearly tells you that this type of operation is not supported.

An AI agent that operates databases through natural-language conversation. Built with Spring Boot + Spring AI: a shared tool layer serves both the built-in chat (Function Calling) and external MCP clients (Streamable HTTP), paired with a Vue3 workbench.

## Features

- **Chat workbench (3-pane)**: top light navigation; session pane, chat pane (Markdown-rendered answers), SQL console + result panel
- **Natural language queries**: the agent inspects schemas, generates & runs SQL, and shows result cards with AI commentary
- **Datasource management**: MySQL / PostgreSQL CRUD, connection test, read-only switch; passwords stored with AES-GCM; dynamic Hikari pools
- **Model management**: provider / model-ID dictionary cascading, base URL & API key, enable switch
- **Sessions & confirmation**: WebSocket message stream; write operations require user confirmation (60s countdown)
- **Developer-mode delete guard**: DELETE / DROP / TRUNCATE are blocked by default; the UI guides you to enable the switch
- **No-datasource closure**: when no datasource exists, asking a question pops up a "new datasource" form; saving it auto-resends your question
- **SQL console**: run ad-hoc SQL directly (no confirmation), results join the result panel
- **Charts (ECharts)**: results of statistical SQL (aggregate functions / GROUP BY) are auto-tagged by the backend, so result cards render a chart by default (tagged "统计图表", switchable bar / line / pie); the agent can also call `render_chart` explicitly to choose the chart type (tagged "AI 图表"); multi-column plain queries default to the table view, and any result card can be switched between table and chart
- **MCP server**: `/mcp` (Streamable HTTP) exposes 6 tools for Claude Desktop / Cursor etc.
- **Persistence**: sessions, messages and results are stored in SQLite and restored on refresh
- **Credential isolation**: DB accounts / passwords / hosts never reach the LLM; tool error messages are masked before returning
- **File logging**: console + rolling file `logs/database-operation-agent.log` (gzip archives)

## Architecture

```
Browser SPA (Vue3 + ant-design-vue)
        │ REST /api/*            │ WebSocket /ws/session/{id}
Spring Boot 3.5 + Spring AI 1.1
  REST controllers · WS chat orchestration · MCP Server /mcp
                    │
     Shared tool layer (single implementation)
  list_datasources · list_tables · get_table_schema
  execute_query (limits/timeout) · execute_update (confirm + delete guard)
  render_chart (aggregate SQL → ECharts)
        │
  SQLite (config/session store) · MySQL / PostgreSQL (dynamic pools)
```

## Tech Stack

Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.8 · Spring AI MCP Server (Streamable HTTP) · SQLite · HikariCP · Vue 3.5 + Pinia + Vue Router · ant-design-vue 4.2 · Vite 8

## Quick Start

Requires **JDK 21** (set `JAVA_HOME` explicitly if your default JDK is older).

```bash
export JAVA_HOME=/path/to/jdk-21
./mvnw clean package
java -jar target/database-operation-agent-1.0.0.jar
# open http://localhost:8080  → redirects to /chat
```

Seed data (`src/main/resources/sql/update.sql`) is applied on first start: two test datasources plus dictionaries / system config. No API keys are stored in the repo — add models from the "Models" page.

## MCP Integration

Endpoint: `http://localhost:8080/mcp` with tools `list_datasources`, `list_tables`, `get_table_schema`, `execute_query`, `execute_update`, `render_chart`.

```json
{
  "mcpServers": {
    "database-operation-agent": { "url": "http://localhost:8080/mcp" }
  }
}
```

## Acceptance Walkthrough

1. Ask `帮我查询用户列表？` → answer with SQL + result card (max 10 rows by default)
2. Ask `系统有多少用户？` → count answer + result card
3. Console: `select * from users` → result card marked as "控制台"
4. Start a write operation → confirmation card → try both "cancel" and "confirm"
5. Console: `delete from users where id = -1` → "delete denied" dialog guiding you to System Config
6. Ask `各状态用户的数量分布` → the result card auto-renders a bar chart tagged "AI 图表"; switch between pie / line and table views

## Security Notice

- **Credentials never reach the LLM**: only datasource name / type, schema and query results are visible to the model; tool error messages are masked (users / hosts / passwords / JDBC URLs → `***`) before returning.
- The test datasources (`192.168.110.88`) inside `update.sql` are **for local development and demos only**; the repository contains no LLM API keys. Replace them with your own credentials and rotate `app.crypto.key` before any real deployment.

## Development

```bash
./mvnw spring-boot:run -Pskip-frontend   # backend only
cd frontend && npm install && npm run dev # frontend with proxy to :8080
./mvnw test                               # unit tests
```

## Logging

Console + rolling file `logs/database-operation-agent.log` (override the directory with `LOG_DIR`); daily + 50MB rotation, 30 days / 1GB cap, gzip archives; business package at DEBUG.

## License

[MIT](LICENSE)
