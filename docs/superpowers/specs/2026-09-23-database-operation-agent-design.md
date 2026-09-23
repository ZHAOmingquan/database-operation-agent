# 数据库操作智能体 — 设计文档

- 日期：2026-09-23
- 状态：已确认（用户批准）
- 包名：`com.mingzy.dbagent`
- 仓库：https://gitee.com/mingzy/database-operation-agent.git

## 1. 目标与范围

构建一个基于 Spring Boot + Spring AI 的数据库操作智能体：用户通过自然语言对话或手写 SQL 对数据库执行增删改查；智能体的数据库操作能力同时封装为 MCP（Model Context Protocol）标准接口，供外部大模型客户端（Claude Desktop、Cursor 等）调用。前后端打包为单体 jar，开箱即用。

范围内：
1. 数据源管理（MySQL、PostgreSQL）：增删改查、连接测试、动态生效、只读开关
2. 大模型管理：可配置/启用模型（名称、供应商、baseUrl、apiKey、模型ID、temperature、maxTokens）；模型ID 为字典管理，按厂商动态加载下拉
3. 元数据管理 + SQL 执行（增删改查）封装为 MCP 工具，同时供内置 ChatClient function calling 使用
4. 会话功能：WebSocket 交互式会话；右侧上方 SQL 控制台；右侧下方动态结果集列表
5. 前端 Ant Design Vue 3 SPA，构建产物打包进后端 `static/`
6. README 初始化

范围外（YAGNI）：多用户权限体系、SQL 审计审批流、更多数据库方言（Oracle/SQLServer）、Anthropic/DashScope 原生适配器。

## 2. 技术栈

| 项 | 选型 |
|---|---|
| 语言/运行时 | Java 21（Temurin 21） |
| 后端框架 | Spring Boot 3.5.x |
| AI 框架 | Spring AI 1.0.x GA（实施时以 Maven Central 最新稳定 GA 为准） |
| MCP | `spring-ai-starter-mcp-server-webmvc`，Streamable HTTP，端点 `/mcp` |
| 配置存储 | SQLite（`org.xerial:sqlite-jdbc`，文件 `./data/agent.db`，WAL 模式） |
| 持久化访问 | spring-jdbc `JdbcTemplate`（表数量少，避免 ORM 兼容性坑） |
| 初始化脚本 | `spring.sql.init`：`mode=always`、`continue-on-error=true`、`schema-locations=classpath:sql/schema.sql`、`data-locations=classpath:sql/update.sql`（两者均幂等可重复执行） |
| 连接池 | HikariCP（配置库存一个池 + 每个受管数据源一个池） |
| JDBC 驱动 | `com.mysql:mysql-connector-j`、`org.postgresql:postgresql` |
| 前端 | Vue 3 + Vite + Ant Design Vue 3 + vue-router + axios + 原生 WebSocket 封装 + markdown-it（Markdown 渲染）+ ECharts（图表） |
| 构建 | 单 Maven 工程 + `frontend/` 前端目录；`frontend-maven-plugin` 在 package 阶段构建前端并拷贝到 `target/classes/static`；`-Pskip-frontend` 可跳过 |

## 3. 总体架构

**核心架构决策（方案 A：共享工具层）**：核心数据库操作能力只实现一份，注册为 Spring AI `ToolCallbackProvider` Bean：

- **对内**：`ChatClient` 挂载同一批工具做 function calling（自然语言 → 元数据探查 → SQL 执行 → 自然语言回答）
- **对外**：MCP Server（Streamable HTTP）自动发现同一批工具，暴露为标准 MCP tools
- 无网络回环、无自连接依赖，工具行为两端完全一致

```
浏览器 (Vue3 SPA, 打包进 static/)
   │  REST + WebSocket
   ▼
web 层 (REST Controllers, WS Handler)
   ▼
chat 会话编排 (ChatService, 确认机制) ──► ChatClient ◄── Spring AI (OpenAI 兼容)
   ▼                                        │ tool call
tool 共享工具层 (ToolCallbackProvider) ◄────┘
   │                 ▲
   │                 └──── MCP Server (Streamable HTTP /mcp) ◄── 外部大模型客户端
   ▼
executor SQL执行器 ── metadata 元数据服务 (MySQL/PG 方言)
   ▼
datasource 动态数据源管理 (HikariCP 池 Map)
   ▼
受管数据库 (MySQL / PostgreSQL)         配置库 SQLite (./data/agent.db)
```

## 4. 模块划分

```
com.mingzy.dbagent
├── config/       Spring 配置、WebSocket 配置、MCP 配置、SPA 转发、ChatClient 工厂配置
├── common/       统一响应 Result、全局异常、AES-GCM 加解密工具、JSON 工具
├── datasource/   数据源实体/DAO、连接测试、DynamicDataSourceManager（HikariCP 池 Map，增删改即时刷新）
├── model/        大模型实体/DAO、启用切换、ChatClientFactory（按 DB 配置动态构建 OpenAI 兼容客户端）
├── dict/         字典实体/DAO、按 dict_type 与 parent_key 查询（厂商→模型ID 两级）
├── sysconfig/    系统配置实体/DAO/服务（sys_config 表，含开发者模式开关）
├── metadata/     元数据服务接口 + MySQL/PostgreSQL 两个方言实现（表、视图、列、主键、注释）
├── executor/     SqlExecutor：语句分类（query/update/ddl）、SELECT 自动注入 LIMIT、超时、结果行截断
├── tool/         共享工具：list_datasources / list_tables / get_table_schema / execute_query / execute_update
├── mcp/          MCP Server 工具注册（自动发现 tool 包）
├── chat/         会话/消息服务、ChatService（编排 LLM 对话循环）、写操作确认（CompletableFuture）、WS Handler
└── web/          REST Controllers（datasource/model/dict/sysconfig/session/confirm/console）
```

## 5. 数据模型（SQLite，8 张表）

所有表由 `schema.sql` 以 `CREATE TABLE IF NOT EXISTS` 创建（含必要唯一索引），种子数据由 `update.sql` 以 `INSERT OR IGNORE INTO ... VALUES` 幂等插入。

1. `ds_datasource`：id、name(唯一)、db_type(mysql/postgresql)、host、port、database_name、username、password(AES-GCM 密文)、extra_params、read_only(0/1)、created_at、updated_at
2. `ai_model`：id、name、provider(字典 key)、base_url、api_key(AES-GCM 密文)、model_id、temperature、max_tokens、enabled(0/1)、created_at、updated_at
3. `sys_dict`：id、dict_type(model_provider / model_id)、dict_key、dict_label、parent_key(模型ID 归属的厂商 key)、sort、enabled
4. `chat_session`：id、title、datasource_id、model_id、created_at、updated_at
5. `chat_message`：id、session_id、role(user/assistant/tool)、content、tool_calls_json、status、created_at
6. `sql_result`：id、session_id、message_id、datasource_id、sql_text、result_type(query/update/ddl/error)、columns_json、rows_json(最多截断存 500 行)、row_count、affected_rows、elapsed_ms、ai_comment(智能体解读)、source(agent/console)、status(success/error/pending)、error_message、created_at
7. `confirm_request`：id、session_id、message_id、datasource_id、sql_text、status(pending/approved/rejected/expired)、created_at、expires_at
8. `sys_config`：id、config_key(唯一)、config_value、description、updated_at（系统配置；`developer_mode` 控制删除操作）

## 6. 核心流程

### 6.1 自然语言查询（WebSocket）
1. 前端通过 `ws://.../ws/session/{sessionId}` 发送用户消息（含 sessionId、当前 datasourceId）
2. 数据源前置校验（业务闭环）：会话未关联数据源且系统无任何数据源时，WS 推送 `no_datasource` 事件（hasAnyDatasource=false）并终止本轮；前端弹出新建数据源表单，创建成功后自动以新数据源重发原消息；系统已有数据源但未选择时同样推送 `no_datasource`（hasAnyDatasource=true），前端提示先在顶栏选择数据源
3. ChatService 持久化消息 → 组装上下文（系统提示词：当前数据源、回答规范、默认 LIMIT 10 说明；最近 20 条历史消息）→ 调 ChatClient（携带共享工具）
4. LLM 可能先调 `list_tables` / `get_table_schema`，再调 `execute_query`
5. 执行器对 SELECT 自动注入 `LIMIT 10`（用户显式要求更多时按用户值），结果写入 `sql_result`（source=agent）并即时 WS 推送 `result` 事件
6. 工具结果回填 LLM → 生成自然语言答案 → WS 推送 `message` 事件；若答案对应某次查询，则同时把答案写入该 `sql_result.ai_comment` 并推送 `result_update` 事件
7. 前端：左侧消息流显示自然语言答案（工具调用过程可折叠展示），右下结果集列表新增/更新卡片

### 6.2 写操作确认（内部会话）
1. LLM 调 `execute_update` → 工具检测到写语句 → 创建 `confirm_request`（status=pending, expires_at=+60s）→ WS 推送 `confirm_request` 事件（含 SQL、数据源、请求ID）
2. 工具线程以 `CompletableFuture.get(60s)` 挂起等待
3. 用户点击"确认执行" → `POST /api/confirm/{id}/approve` → 真正执行 SQL → `future.complete(执行结果)` → WS 推送执行结果
4. 用户点击"取消" → `reject` → `future.complete(被拒绝)`；超时 → `expired`；两种情况都以文字消息回填 LLM，由其告知用户
5. 只读数据源：写操作直接拒绝（无论是否确认）

### 6.3 SQL 控制台（右侧上方）
1. 用户选择数据源、输入 SQL、点击执行 → `POST /api/sessions/{id}/console/execute`
2. 用户手写 SQL 无需确认，直接执行（查询同样默认 LIMIT 10，可通过按钮选择行数上限）
3. 结果写入 `sql_result`（source=console）并 WS 推送，右下结果集列表展示

### 6.4 MCP 对外
- 外部大模型客户端连接 `http://<host>:8080/mcp`（Streamable HTTP）调用工具集
- `execute_update` 带 MCP 工具注解 `destructiveHint=true`，由 MCP 客户端负责向用户确认；服务端仍受只读数据源开关约束
- 工具入参中的数据源以**数据源名称**（唯一）指定，对 LLM 更友好

### 6.5 删除操作守卫（开发者模式）
1. 系统配置 `developer_mode`（默认关闭）控制删除类操作：DELETE / DROP / TRUNCATE
2. 关闭时：工具层在确认流程**之前**拦截（对内置对话与 MCP 同时生效），返回文本“请开启开发者模式，确保你对删除后果了解”；内部会话额外通过 WS 推送 `delete_denied` 事件，前端弹出提示框（提供前往“系统配置”入口）
3. 开启时：删除操作仍走 6.2 的逐笔确认流程（双重保护）
4. 只读数据源：无论如何写操作一律拒绝（见 6.2 第 5 条）

## 7. 共享工具清单（MCP 与内置 ChatClient 同一批）

| 工具名 | 说明 | 入参 | 注解 |
|---|---|---|---|
| `list_datasources` | 列出全部数据源及类型 | – | readOnlyHint |
| `list_tables` | 列出某数据源的表与视图 | datasourceName | readOnlyHint |
| `get_table_schema` | 表/视图结构（列、类型、可空、主键、注释） | datasourceName, tableName | readOnlyHint |
| `execute_query` | 执行 SELECT（自动注入 LIMIT 10） | datasourceName, sql, limit? | readOnlyHint |
| `execute_update` | 执行 INSERT/UPDATE/DELETE/DDL（内部会话走确认；MCP 走 destructiveHint） | datasourceName, sql | destructiveHint |
| `render_chart` | 执行统计聚合 SQL 并把结果推送为图表配置（ECharts：bar/line/pie）在前端结果区展示（迭代需求 2） | datasourceName, sql, chartType, title?, xField?, yField? | readOnlyHint |

## 8. REST / WS / MCP 接口面

REST（统一前缀 `/api`）：
- 数据源：`GET|POST /datasources`、`PUT|DELETE /datasources/{id}`、`POST /datasources/test`（未保存表单亦可测）
- 元数据：`GET /datasources/{id}/tables`、`GET /datasources/{id}/tables/{table}/schema`
- 模型：`GET|POST /models`、`PUT|DELETE /models/{id}`、`POST /models/{id}/enable`、`POST /models/test`
- 字典：`GET|POST /dicts`、`PUT|DELETE /dicts/{id}`、`GET /dicts/model_ids?provider=`（联动下拉）
- 会话：`GET|POST /sessions`、`DELETE /sessions/{id}`、`GET /sessions/{id}/messages`、`GET /sessions/{id}/results`
- 控制台：`POST /sessions/{id}/console/execute`
- 确认：`POST /confirm/{id}/approve`、`POST /confirm/{id}/reject`
- 系统配置：`GET /configs`、`PUT /configs/{key}`（值 true/false）

WebSocket：`/ws/session/{sessionId}`，事件类型：`message`、`result`、`result_update`、`confirm_request`、`confirm_result`、`delete_denied`、`no_datasource`、`error`。

MCP：`/mcp`（Streamable HTTP）。

SPA 转发：非 `/api`、`/ws`、`/mcp` 的路径转发到 `index.html`（前端路由用 History 模式）。

## 9. 前端页面设计

布局：Ant Design Vue `Layout` + **顶部水平导航**（明亮主题、白底；迭代需求 2 起不再使用左侧全局菜单，见 16.1）。

### 9.1 对话工作台 `/chat`（核心页面，迭代需求 2 起为三栏布局）
- 全局：顶部水平导航 + 明亮主题（见 16.1；不再使用左侧全局菜单与顶栏会话下拉）
- 左栏（会话列表，240px）：会话列表（当前高亮）+ 新建会话 + 删除（二次确认）；发送消息后会话标题本地同步为问题摘要
- 中栏（对话）：工具条（数据源选择器、模型选择器（仅列出 enabled 模型）、WS 连接状态徽标）；消息流（用户气泡、助手气泡（Markdown 渲染）、工具调用过程折叠块、**写操作确认卡片**（SQL 高亮 + 确认/取消按钮 + 倒计时）、错误提示）；底部输入框 + 发送
- 右栏（42%）：上方 SQL 控制台（数据源选择、SQL 文本域（等宽字体）、执行按钮、行数上限选择、格式化按钮）；下方结果集列表
- 结果集卡片：数据源、SQL（可复制）、耗时/行数/影响行数、状态标签（成功/失败/待确认）、**表格 / 图表视图切换**（ECharts 柱状/折线/饼图；AI 出图时默认图表视图并标注「AI 图表」）、`ai_comment` 的"AI 解读"区块（Markdown 渲染）；支持折叠与清空
- 历史恢复：进入页面时 REST 拉取 messages/results；WS 断线自动重连（指数退避），重连后按 messageId 增量补齐
- 无数据源引导：系统中没有任何数据源时，用户直接提问 → 弹出「新建数据源」表单（与数据源管理页共用同一表单组件 `DatasourceFormModal`）；保存成功后自动将新数据源设为当前选择并重发原问题，形成业务闭环

### 9.2 数据源管理 `/datasources`
表格（名称、类型、主机、库名、只读、操作）+ 新建/编辑弹窗表单（类型下拉 mysql/postgresql、host、port、database、username、password、额外参数、只读开关）+ 行内"测试连接"按钮 + 删除二次确认。

### 9.3 模型管理 `/models`
表格（名称、供应商、模型ID、baseUrl、启用开关、操作）+ 新建/编辑弹窗（供应商下拉来自字典 `model_provider`；模型ID 下拉随供应商联动加载字典 `model_id`，`parent_key=供应商`，支持自定义输入）+ "测试连接"按钮（发一条最小请求验证）+ 启用互斥由服务端保证：同一时间**最多一个**模型 `enabled=1`，启用新模型时自动禁用其他。

### 9.4 字典管理 `/dict`
按 dict_type 分 Tab（model_provider / model_id）；model_id 记录维护 parent_key（所属厂商）；支持增删改、启用/停用、排序。

### 9.5 系统配置 `/configs`
配置项列表（当前：开发者模式开关）。开发者模式为布尔开关：开启时二次确认弹窗（提示删除后果）；关闭时删除类操作（DELETE/DROP/TRUNCATE）被拦截，对话工作台/控制台弹出提示框“请开启开发者模式，确保你对删除后果了解”，并提供前往本页的入口。

## 10. 错误处理策略

| 场景 | 处理 |
|---|---|
| 数据源连接失败 | 表单/结果卡片展示原始异常信息；连接测试接口返回结构化错误 |
| SQL 语法/执行错误 | 结果卡片红色状态展示错误；错误信息回填 LLM 提示其自我修正（不自动重复执行超过 1 次） |
| LLM 调用失败 | WS 推送 error 事件，前端显示错误气泡；不吞异常 |
| SQL 执行超时 | 默认 30s（`Statement.setQueryTimeout`），可配置 |
| 确认超时 | 60s 自动置 expired，回填 LLM "用户未在时限内确认" |
| 删除操作被拦截（开发者模式关闭） | 工具返回拒绝文本（LLM 转述）；内部会话 WS 推送 `delete_denied`，前端弹出提示框并提供开启入口 |
| 提问时无可用数据源 | 系统无任何数据源：WS 推送 `no_datasource`，前端弹新建数据源表单，创建成功后自动重发原问题；已有数据源未选择：提示先在顶栏选择 |
| WS 断线 | 前端指数退避重连 + 增量拉取补齐 |
| MCP 调用错误 | 以 MCP 工具错误结果返回（异常信息文本化） |

## 11. 初始化数据与验收标准

### 11.1 幂等种子数据（update.sql）
- 字典（dict_type=model_provider）：deepseek、qwen、glm、kimi、minimax 五家厂商；字典（dict_type=model_id）按 parent_key 各预置 2 个：deepseek-v4-flash/pro、qwen3.8-max/flash、glm-5.3/flash、k3/kimi-for-coding、MiniMax-M3/M2.7。全部厂商统一走 OpenAI 兼容适配器，差异仅在 baseUrl
- 系统配置（sys_config）：`developer_mode=false`（开发者模式默认关闭；开启后才允许执行 DELETE/DROP/TRUNCATE）
- 测试模型：MiniMax-M3（provider=minimax，baseUrl=`https://api.minimaxi.com/v1`，apiKey 使用 `docs/测试用大模型配置.md` 中的测试密钥，modelId=MiniMax-M3，enabled=1）；README 标注密钥仅供测试（**迭代需求 2 已整体移除此种子**：仓库不留任何模型密钥配置，见 16.6）
- 测试数据源：`mysql-mytest`（jdbc:mysql://192.168.110.88:3306/mytest，root）、`pg-mytest`（jdbc:postgresql://192.168.110.88:5432/mytest，ming），密码以 AES-GCM 密文写入
- 数据库密码与 apiKey 的 AES 密钥来自 `application.yml`（给出默认值，README 提示生产更换）

### 11.2 验收用例（人工走查）
1. 会话输入"帮我查询用户列表？" → 智能体自动发现用户表 → 右下结果集表格正确展示（默认 10 行），左侧给出自然语言说明与所用 SQL
2. 会话输入"系统有多少用户？" → 右下结果卡片展示 `select count(*) from ...` 与结果，且"AI 解读"区块展示"系统有 XXX 个用户"；左侧会话同步回答
3. SQL 控制台输入 `select * from users` → 右下结果集展示表格
4. 外部 MCP 客户端连接 `http://localhost:8080/mcp` → 能看到 5 个工具并可调用 `list_datasources`
5. 全新环境（无数据源）会话中输入"帮我查询用户列表？"→ 弹出新建数据源表单 → 填写保存成功 → 自动以新数据源重发原问题并正常返回查询结果（业务闭环）

## 12. 测试策略

- 单元测试（JUnit 5 + AssertJ）：
  - `SqlClassifier`：语句分类（query/update/ddl）
  - `LimitInjector`：SELECT 自动 LIMIT 注入（含已有 LIMIT、多语句、注释场景）
  - AES-GCM 加解密往返
  - 字典与数据源 DAO（内存 SQLite）
  - `ChartConfigBuilder`：图表类型归一与 x/y 字段自动推导（迭代需求 2）
  - `SensitiveDataMasker`：JDBC URL / Access denied / PG 认证失败 / password= 等凭据形态脱敏（迭代需求 2）
- 集成测试（可选，需 192.168.110.88 网络可达）：MySQL/PG 连接测试、元数据查询
- 验收：第 11.2 节用例人工走查
- 每个实施阶段结束运行 `mvn test` 并汇报真实输出

## 13. 构建与运行

- 开发：`mvn spring-boot:run`（后端）+ `cd frontend && npm run dev`（Vite 代理 `/api`、`/ws`、`/mcp`）
- 打包：`mvn clean package`（自动构建前端进 `static/`；`-Pskip-frontend` 仅打包后端）
- 运行：`java -jar target/database-operation-agent-*.jar` → `http://localhost:8080`
- 配置库文件：`./data/agent.db`（首次启动自动创建）

## 14. 假设与风险

1. Spring AI 1.0 GA 的 MCP Streamable HTTP 配置项以实施时的官方文档为准（计划阶段通过 Maven 验证版本与依赖可用性）
2. `frontend-maven-plugin` 需要下载 Node 发行包；若构建环境无外网，退化为"本地已装 Node 手动构建 + 拷贝 static"并写入 README
3. 测试用数据库 192.168.110.88 需在验收时可达；不可达时用本地 MySQL/PostgreSQL 容器替代
4. SQLite 并发写入采用 WAL + 服务端串行写（synchronized 写入方法）
5. MiniMax-M3 走 OpenAI 兼容协议（`/v1/chat/completions`），工具调用（function calling）能力以实测为准；若该模型不支持 tools，则回退为"LLM 产出 SQL、后端解析执行"的降级路径仅作用于该模型
6. `docs/测试用大模型配置.md` 含真实测试密钥，README 明确其仅用于本地测试

## 15. 验收清单（Definition of Done）

- [ ] `mvn clean package` 一条命令产出含前端的可运行 jar
- [ ] 数据源管理：MySQL/PostgreSQL 增删改查、测试连接、只读开关动态生效
- [ ] 模型管理：配置/启用模型；厂商→模型ID 字典联动下拉
- [ ] 工具能力对内（ChatClient）与对外（`/mcp`）均可用
- [ ] 会话：WS 交互、写操作确认卡片、SQL 控制台、结果集列表、历史持久化
- [ ] 无数据源闭环：无可用数据源时提问弹出新建数据源表单，创建成功后自动完成查询
- [ ] 系统配置：开发者模式开关；关闭时删除类操作（DELETE/DROP/TRUNCATE）被拦截并弹提示框，开启后删除仍逐笔确认
- [ ] 验收用例 1、2 通过
- [ ] README 初始化完成（架构、快速开始、配置、MCP 接入说明、测试密钥警示）

## 16. 迭代需求 2（2026-09-23 交付，实施与实测见计划 Task 31）

用户验收后追加的 5 项需求的落地设计。

### 16.1 顶部导航 + 明亮主题
- 全局布局由「左侧菜单」改为「顶部水平导航」：`a-layout-header`（白底、56px、底部分隔线）+ `theme="light"` 水平 Menu，logo 使用主题蓝（`#1677ff`）。
- 5 个路由入口（对话工作台 / 数据源管理 / 模型管理 / 字典管理 / 系统配置）全部位于顶部导航；内容区高度自适应（`calc(100% - 56px)`）。

### 16.2 Markdown 渲染
- 引入 markdown-it：`html:false`（防注入）、`breaks`、`linkify`，外链统一加 `target=_blank rel=noopener noreferrer`。
- 助手消息（MessageItem）与结果卡片 AI 解读（ResultCard）统一经 `frontend/src/utils/markdown.js` 的 `renderMarkdown()` 渲染，样式由全局 `.markdown-body` 提供。

### 16.3 三栏工作台 + ECharts 图表
- `/chat` 三栏：左栏会话列表（新建 / 切换 / 删除，240px）；中栏对话（工具条 = 数据源、模型选择器 + WS 连接状态；消息流 + 输入框）；右栏 42%（上 SQL 控制台、下结果集列表）。
- **数据契约（`sql_result.chart_config`）**：新增 TEXT 列，存 `{"chartType":"bar|line|pie","title":…,"xField":…,"yField":…}`；schema.sql 建表包含该列并以 `ALTER TABLE … ADD COLUMN` 幂等迁移旧库（重复执行由 `continue-on-error` 容忍）。
- **AI 自动出图（`render_chart` 工具，第 7 节工具清单第 6 项）**：后端执行聚合 SQL → `ChartConfigBuilder` 组装图表配置（xField 缺省取第一列、yField 缺省取第一个数值列，无法识别数值列时返回可读错误）→ 经会话钩子落库并 WS 推送 → 前端结果卡片默认以图表视图展示并标注「AI 图表」；系统提示词规则：统计分析、趋势 / 分布 / 占比、分组对比或明确要求图表时，必须写聚合 SQL 并调用该工具。
- **手动切换**：任意结果卡片支持「表格 / 图表」切换与图表类型（柱状 / 折线 / 饼图）选择；前端 `ChartView.vue` 按需注册 echarts/core（Bar/Line/Pie + Grid/Legend/Title/Tooltip + CanvasRenderer），ResizeObserver 自适应尺寸。

### 16.4 凭据隔离（数据库账号密码不发给大模型）
- 边界：LLM 与 MCP 客户端可见面仅「数据源名称 / 类型 + 元数据 + SQL 执行结果 +（脱敏后的）错误信息」；密码、用户名、主机不进入提示词、工具返回值或 MCP 响应。
- 审计结论：API 返回体（DatasourceView）无密码字段（仅 hasPassword）；系统提示词仅含 name/dbType；代码无凭据日志。
- 风险点修复：连接池 fail-fast 与驱动异常消息可能携带 `Access denied for user 'u'@'h' (using password: YES)`、JDBC URL 或 `password=` 明文——新增 `SensitiveDataMasker`（JDBC_URL / ACCESS_DENIED / PG_AUTH_FAILED / KEY_VALUE_SECRET 四类 pattern），在**全部工具返回值出口统一脱敏**（7 处，替换为 `***` 占位符），对内置对话与 MCP 同时生效。
- 设计边界：脱敏仅作用于回传 LLM 的文本；本地日志与前端结果卡片保留原始错误信息便于排障（前端展示属可信通道）。

### 16.5 日志落盘（logback）
- `logback-spring.xml`：控制台 + 滚动文件双 appender；文件路径 `${LOG_DIR:-logs}/database-operation-agent.log`（默认应用工作目录 `logs/`，环境变量可覆盖）；按天 + 单文件 50MB 滚动、保留 30 天、总量上限 1GB、历史归档 gzip。
- 级别：根 INFO；业务包 `com.mingzy.dbagent` DEBUG；`com.zaxxer.hikari` INFO（抑制心跳刷屏）。
- `.gitignore` 增加 `logs/`，运行日志不进仓库。

### 16.6 种子数据调整
- 为配合 16.4，`update.sql` 移除「测试模型 MiniMax-M3」种子（含加密后的测试密钥密文）；全新环境首启仅预置两个测试数据源与字典 / 系统配置。测试模型请在「模型管理」页面自行新增。
