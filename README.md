# 数据库操作智能体（Database Operation Agent）

通过自然语言对话直接对数据库进行查询与增删改查的智能体。基于 Spring Boot + Spring AI 构建：内置工具层同时服务于内部对话（Function Calling）与外部 MCP 客户端（Streamable HTTP），配套 Vue3 对话工作台。

## 功能清单

- **对话工作台**：自然语言提问 → 智能体自动探查表结构、生成并执行 SQL、以结果卡片 + AI 解读展示
- **数据源管理**：MySQL / PostgreSQL 增删改查、测试连接、只读开关；密码 AES-GCM 加密存储；动态 Hikari 连接池
- **大模型管理**：厂商 / 模型 ID 字典联动下拉，base_url / api_key / 参数配置，启用开关
- **会话与确认**：WebSocket 实时消息流；写操作（INSERT/UPDATE/DDL）弹出确认卡片（60s 倒计时），用户确认后执行
- **开发者模式删除守卫**：默认禁止 DELETE/DROP/TRUNCATE；被拦时弹框引导前往「系统配置」开启
- **无数据源闭环**：未配置任何数据源时提问会弹出「新建数据源」表单，保存后自动重发刚才的问题
- **SQL 控制台**：手写 SQL 直接执行（无需确认），结果同样落库并进入结果集面板
- **MCP Server**：`/mcp`（Streamable HTTP）暴露 5 个工具，供 Claude Desktop / Cursor 等外部客户端调用
- **历史持久化**：会话 / 消息 / 执行结果全部落 SQLite，刷新页面自动恢复

## 架构

```
┌───────────────────────────────────────────────────────────────┐
│  浏览器 SPA（Vue3 + ant-design-vue）                           │
│  对话工作台 · 数据源 · 模型 · 字典 · 系统配置                   │
└──────────────┬──────────────────────────────┬─────────────────┘
               │ REST /api/*                  │ WebSocket /ws/session/{id}
┌──────────────▼──────────────────────────────▼─────────────────┐
│  Spring Boot 3.5 + Spring AI 1.1                              │
│  ┌─────────────┐  ┌────────────────┐  ┌──────────────────┐    │
│  │ REST 控制器  │  │ WS 会话编排     │  │ MCP Server /mcp  │    │
│  │（配置 CRUD） │  │（确认/结果推送）│  │（Streamable HTTP）│    │
│  └──────┬──────┘  └───────┬────────┘  └────────┬─────────┘    │
│         └─────────────────┴──────────┬─────────┘              │
│                       工具层（共享单一实现）                    │
│   list_datasources · list_tables · get_table_schema           │
│   execute_query（限行/超时） · execute_update（确认+删除守卫）   │
└──────────────┬──────────────────────────────┬─────────────────┘
               │                              │
   SQLite（配置与会话库）             MySQL / PostgreSQL（业务库，动态连接池）
```

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 运行环境 | Java | 21 |
| 后端框架 | Spring Boot | 3.5.16 |
| AI 编排 | Spring AI（OpenAI 兼容协议） | 1.1.8 |
| MCP | Spring AI MCP Server（Streamable HTTP） | 1.1.8 |
| 配置/会话库 | SQLite（sqlite-jdbc） | 3.x |
| 连接池 | HikariCP | Spring Boot 内置 |
| 前端 | Vue 3 + Vue Router + Pinia | 3.5 / 5.3 / 4.0 |
| UI 组件 | ant-design-vue | 4.2.6 |
| 前端构建 | Vite | 8.3.0 |
| JDBC 驱动 | mysql-connector-j / postgresql | Spring Boot 管理 |

## 快速开始

要求：**JDK 21**（构建与运行均需要；若开发机默认 JDK 较旧，请显式指定 `JAVA_HOME`）。

```bash
# 1) 一条命令构建（自动下载 node、构建前端并打包进 jar）
export JAVA_HOME=/path/to/jdk-21
./mvnw clean package

# 2) 运行（默认 8080 端口，配置库自动建在 ./data/agent.db）
java -jar target/database-operation-agent-1.0.0.jar

# 3) 打开对话工作台
# http://localhost:8080  →  自动跳转 /chat
```

首次启动会自动写入种子数据（`src/main/resources/sql/update.sql`）：两个测试数据源与一个测试模型（MiniMax-M3，已启用）。

## 配置说明（页面）

- **数据源管理**：新增 MySQL / PostgreSQL；「测试连接」即时验证；「只读」数据源会拒绝写操作。密码经 AES-GCM 加密后入库，页面不回显。
- **模型管理**：厂商与模型 ID 来自字典两级联动；填写 base_url 与 api_key 后启用。对话使用「已启用」的模型。
- **字典管理**：`model_provider`（厂商）与 `model_id`（模型 ID，`parent_key` 指向厂商）两类字典，可自行增补。
- **系统配置**：`developer_mode` 开发者模式开关 —— **开启后才允许**执行 DELETE / DROP / TRUNCATE（默认关闭，删除被拦截并引导开启）。

## MCP 接入

服务启动后即暴露 Streamable HTTP 端点：`http://localhost:8080/mcp`，包含 5 个工具：
`list_datasources`、`list_tables`、`get_table_schema`、`execute_query`、`execute_update`。

常见 MCP 客户端配置（以支持 Streamable HTTP 的客户端为例）：

```json
{
  "mcpServers": {
    "database-operation-agent": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

curl 快速自测（先 initialize，取响应头 `Mcp-Session-Id`，再发 `notifications/initialized`，随后即可 `tools/list` / `tools/call`）：

```bash
SID=$(curl -s -o /dev/null -w '%header{Mcp-Session-Id}' -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')

curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

## 验收用例

在对话工作台（默认已选数据源与模型）依次验证：

1. 输入 `帮我查询用户列表？` → 左栏出现回答（附 SQL），右下出现结果卡片（表格，默认最多 10 行）
2. 输入 `系统有多少用户？` → 回答给出计数与查询 SQL，结果卡片展示统计表
3. 控制台输入 `select * from users` → 点击「执行」→ 结果卡片标记来源为「控制台」
4. 会话中发起写操作（如 `把 users 表里 id 为 N 的记录 status 设为 1`）→ 弹出确认卡片 → 分别验证「取消」与「确认执行」
5. 控制台执行 `delete from users where id = -1` → 默认弹框「删除操作被拒绝」，可一键前往系统配置开启开发者模式

## 测试密钥警示

`src/main/resources/sql/update.sql` 中的 MiniMax 测试 API Key（密文）与测试数据源（`192.168.110.88` 上的 MySQL/PostgreSQL）**仅用于本地开发与验收演示**。部署到任何真实环境前，请：删除/替换为自有数据源与模型配置、更换 `app.crypto.key`，切勿在生产使用测试凭据。

## 开发指南

```bash
# 仅后端迭代（跳过前端构建，仍会复制已有 dist）
./mvnw spring-boot:run -Pskip-frontend

# 前端热更（Vite dev server 已配置 /api、/ws 代理到 8080）
cd frontend && npm install && npm run dev

# 单元测试
./mvnw test

# 手动端到端用例（真实调用 MiniMax 与测试库，默认 @Disabled）
JAVA_HOME=/path/to/jdk-21 ./mvnw test -Dtest=ChatE2eIT -Pskip-frontend \
  -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
```

注意：前端构建脚本会自动下载 node v22 到 `target/node`，无需本机预装 Node。

## 目录结构

```
├── src/main/java/com/mingzy/dbagent/
│   ├── datasource/   数据源管理（DAO / 动态连接池 / 测试连接 / REST）
│   ├── model/        大模型管理（配置 / 启用 / REST）
│   ├── dict/         字典（厂商 → 模型 ID 联动）
│   ├── sysconfig/    系统配置（开发者模式）
│   ├── metadata/     库表元数据探查（表 / 视图 / 字段）
│   ├── executor/     SQL 分类 / 执行 / 删除守卫（DeleteGuard）
│   ├── tool/         工具层（对话 Function Calling 与 MCP 共用）
│   ├── chat/         会话域（WS 编排 / 确认 / 历史 / REST）
│   └── common/       统一返回 / 全局异常 / AES-GCM 加解密
├── src/main/resources/
│   ├── application.yml
│   └── sql/          schema.sql（建表）· update.sql（幂等种子）
├── frontend/         Vue3 SPA（构建产物自动复制进 jar 的 static/）
└── docs/superpowers/ 设计文档（specs）与实施计划（plans）
```

## License

[MIT](LICENSE)
