# 数据库操作分析智能体（Database Operation Agent）

> - 用自然语言对话，就可以对你的数据库数据了如指掌。单包独立运行无其他依赖，可独立使用，也提供了MCP服务接口可集成到你的应用中。
> - 核心价值：数据安全！！！**数据、账号密码全过程不出域**，也不会发给大模型任何数据库中的数据！！！【这是使用MyDBAgent与直接告诉大模型数据库连接操作的本质区别】
> - 当前 agent 仅允许对数据库操作； DDL / DML 之外的其他任何指令或问题，均会明确告知用户不支持此类型操作。
> - **如果这个项目对你有帮助，欢迎到 [Gitee 仓库](https://gitee.com/mingzy/database-operation-agent) 点个 ⭐ Star 支持一下，让更多人发现它！**

通过自然语言对话直接对数据库进行查询与增删改查的智能体。基于 Spring Boot + Spring AI 构建：内置工具层同时服务于内部对话（Function Calling）与外部 MCP 客户端（Streamable HTTP），配套 Vue3 对话工作台。
- 试用地址：https://mydbagent.z-mq.com 

## 功能清单

- **对话工作台（三栏）**：顶部明亮导航；左栏会话列表（新建 / 切换 / 删除）、中栏对话（回答与 AI 解读以 Markdown 渲染）、右栏 SQL 控制台 + 结果集
- **自然语言提问**：智能体自动探查表结构、生成并执行 SQL、以结果卡片 + AI 解读展示
- **数据源管理**：MySQL / PostgreSQL 增删改查、测试连接、只读开关；密码 AES-GCM 加密存储；动态 Hikari 连接池
- **大模型管理**：厂商 / 模型 ID 字典联动下拉，base_url / api_key / 参数配置，启用开关
- **会话与确认**：WebSocket 实时消息流；写操作（INSERT/UPDATE/DDL）弹出确认卡片（60s 倒计时），用户确认后执行
- **多人提问排队**：多用户同时提问时全局 FIFO 排队、**最多 5 路并发处理**（平衡响应速度与模型 RPM 压力）；排队数量实时刷新，**超过 5 时**页面显示「当前正在排队处理问题」横幅与数量，≤5 静默等待，轮到时自动开始；模型调用异常/超限时提示「模型使用超限，无法继续提供服务」
- **开发者模式删除守卫**：默认禁止 DELETE/DROP/TRUNCATE；被拦时弹框引导前往「系统配置」开启
- **无数据源闭环**：未配置任何数据源时提问会弹出「新建数据源」表单，保存后自动重发刚才的问题
- **SQL 控制台**：手写 SQL 直接执行（无需确认），结果同样落库并进入结果集面板
- **图表展示（ECharts）**：统计语句（聚合函数 / GROUP BY）的查询结果由后端自动标记，前端结果卡片默认渲染图表（标注「统计图表」，柱状 / 折线 / 饼图可切换）；智能体也可主动调用 `render_chart` 指定图表类型（标注「AI 图表」）；多列表数据查询默认表格展示，任意结果卡片可手动切换「表格 / 图表」
- **MCP Server**：`/mcp`（Streamable HTTP）暴露 6 个工具，供 Claude Desktop / Cursor 等外部客户端调用
- **历史持久化**：会话 / 消息 / 执行结果全部落 SQLite，刷新页面自动恢复
- **浏览器指纹会话隔离**：会话按浏览器指纹（特征 + 本地随机盐）隔离，多个浏览器互不可见；历史会话列表同样按指纹过滤（详见「浏览器指纹会话隔离」） --此部分可自行替换为账号密码登录，通过账号隔离
- **凭据隔离**：数据库账号 / 密码 / 主机不进入大模型上下文与 MCP 响应；工具异常消息统一脱敏后再回传（详见「凭据安全」）
- **日志落盘**：同时输出控制台与滚动文件 `logs/database-operation-agent.log`（gz 归档）

智能体效果视频：

<video src="docs/数据库智能体.mp4" controls width="100%"></video>

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
│   render_chart（统计聚合 → ECharts 图表）                     │
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
| 图表 / Markdown | ECharts / markdown-it | 6.1 / 15 |
| 日志 | Logback（控制台 + 滚动文件） | Spring Boot 内置 |
| JDBC 驱动 | mysql-connector-j / postgresql | Spring Boot 管理 |

## 快速开始

要求：**JDK 21**（构建与运行均需要；若开发机默认 JDK 较旧，请显式指定 `JAVA_HOME`）。

```bash
# 1) 一条命令构建（自动下载 node、构建前端并打包进 jar）
export JAVA_HOME=/path/to/jdk-21
./mvnw clean package

# 2) 运行（默认 18080 端口，配置库自动建在 ./data/agent.db）
java -jar target/database-operation-agent-1.0.0.jar

# 3) 打开对话工作台
# http://localhost:18080  →  自动跳转 /chat
```

日常部署推荐直接使用 `./deploy.sh`：打包后把 jar 拷到 `deploy/` 再从副本重启（含健康检查）。应用始终运行 `deploy/` 下的副本，避免后续 `mvn package` 重建 `target` jar 时破坏运行中实例的类加载（会导致页面 504 / NoClassDefFoundError）。

### 打包为单体 jar（All-in-One）

`./mvnw clean package` 一条命令产出可直接 `java -jar` 运行的单体 jar，过程全自动：

1. `frontend-maven-plugin` 下载 Node v22 到 `target/node`（首次构建）→ `npm install` → `vite build` 产出 `frontend/dist`
2. `maven-resources-plugin` 清空并拷贝 `frontend/dist` 到 `target/classes/static`
3. `spring-boot:repackage` 把全部依赖与前端静态资源打进 `BOOT-INF/`，生成最终 fat jar

产物：`target/database-operation-agent-1.0.0.jar`（前端、后端、依赖、建库脚本一体；首次启动自动建 SQLite 库与种子数据）。

```bash
./mvnw clean package          # 全量构建（含前端）
./mvnw clean package -Pskip-frontend   # 纯后端迭代，跳过前端（使用已有 dist）
java -jar target/database-operation-agent-1.0.0.jar   # 直接运行
```

前端最低兼容 Chrome 64 / Firefox 60 / Safari 12 内核（构建自动降级语法 + core-js polyfill，详见前端 vite.config.js 注释）。

首次启动会自动写入种子数据（`src/main/resources/sql/update.sql`）：两个测试数据源、字典与系统配置。仓库不包含任何大模型 API Key，测试模型请在「模型管理」页面自行新增。

## 配置说明（页面）

- **数据源管理**：新增 MySQL / PostgreSQL；「测试连接」即时验证；「只读」数据源会拒绝写操作。密码经 AES-GCM 加密后入库，页面不回显。
- **模型管理**：厂商与模型 ID 来自字典两级联动；填写 base_url 与 api_key 后启用。对话使用「已启用」的模型。
- **字典管理**：`model_provider`（厂商）与 `model_id`（模型 ID，`parent_key` 指向厂商）两类字典，可自行增补。
- **系统配置**：`developer_mode` 开发者模式开关 —— **开启后才允许**执行 DELETE / DROP / TRUNCATE（默认关闭，删除被拦截并引导开启）。

## 对话工作台与图表

`/chat` 为三栏布局：左栏会话列表、中栏对话（回答与 AI 解读以 Markdown 渲染）、右栏上方 SQL 控制台、下方结果集。

- 提问涉及统计分析 / 趋势 / 占比 / 分组对比时，智能体自动调用 `render_chart` 工具生成聚合 SQL，结果卡片以柱状 / 折线 / 饼图展示并标注「AI 图表」。
- 任意结果卡片可在右上角手动切换「表格 / 图表」视图与图表类型。

## 浏览器指纹会话隔离

多个浏览器之间的会话数据互不可见：同一浏览器（刷新 / 重启）持有稳定指纹，不同浏览器 / 无头浏览器实例的指纹互不相同。会话列表、消息、结果、SQL 控制台与写操作确认均按指纹隔离；数据源、模型、字典、系统配置为全局配置，不参与隔离。

- **指纹生成**：首次访问时由「浏览器特征（UA / 语言 / 屏幕 / 时区 / CPU 核数等）+ 16 字节随机盐」派生 SHA-256 指纹，缓存于 `localStorage['dbagent_fp']`；后续访问直接复用，浏览器升级或屏幕变化不影响身份。
- **指纹传递**：REST 请求统一携带 `X-Browser-Fingerprint` 头；WebSocket 连接为 `/ws/session/{id}?fp=<指纹>`。
- **越权防护**：携带其他浏览器的指纹访问某会话（messages / results / 控制台 / 确认 / WS 连接）一律拒绝，返回「会话不存在或无访问权限」。
- **升级兼容**：升级前创建的无指纹旧会话不再展示（数据保留在库中）；如需认领，先在浏览器控制台执行 `localStorage.getItem('dbagent_fp')` 取指纹，再对 SQLite 执行：

  ```sql
  UPDATE chat_session SET client_fingerprint='<指纹>' WHERE client_fingerprint IS NULL;
  ```

- **验证**：用两个不同浏览器打开 http://localhost:18080/chat，各建一个会话 —— 列表互不可见；同一浏览器刷新后历史仍在。

## 日志

应用同时输出到控制台与滚动文件：`logs/database-operation-agent.log`（相对启动目录，可用环境变量 `LOG_DIR` 覆盖）。按天 + 单文件 50MB 滚动，历史 gz 压缩，保留 30 天、总量上限 1GB；业务包默认 DEBUG。

## MCP 接入

服务启动后即暴露 Streamable HTTP 端点：`http://localhost:18080/mcp`，包含 6 个工具：

| 工具 | 说明 | 参数 |
|---|---|---|
| `list_datasources` | 列出已配置的数据源（名称/类型/是否只读）。执行 SQL 前先用它确认可用数据源 | 无 |
| `list_tables` | 列出指定数据源中的全部表与视图及其注释 | `datasourceName` |
| `get_table_schema` | 获取指定表的字段结构（列名/类型/可空/主键/默认值/注释） | `datasourceName`、`tableName` |
| `execute_query` | 执行只读查询，默认最多返回 10 行；聚合统计（count/sum/avg/group by）结果会同时在页面结果区以图表展示 | `datasourceName`、`sql`、`limit`（可选，默认 10） |
| `execute_update` | 执行写入/DDL（INSERT/UPDATE/DELETE/CREATE/ALTER 等）。删除类操作（DELETE/DROP/TRUNCATE）需先开启开发者模式；MCP 外部调用请由客户端自行确认后再执行 | `datasourceName`、`sql` |
| `render_chart` | 执行统计 SQL 并把结果以 ECharts 图表（bar/line/pie）展示在页面结果区 | `datasourceName`、`sql`（聚合查询）、`chartType`（bar/line/pie）、`title`/`xField`/`yField`（可选） |

> 凭据安全：数据源账号/密码/主机不进入工具入参与响应；工具异常统一脱敏后回传。

常见 MCP 客户端配置（以支持 Streamable HTTP 的客户端为例）：

```json
{
  "mcpServers": {
    "database-operation-agent": {
      "url": "http://localhost:18080/mcp"
    }
  }
}
```

curl 快速自测（先 initialize，取响应头 `Mcp-Session-Id`，再发 `notifications/initialized`，随后即可 `tools/list` / `tools/call`）：

```bash
SID=$(curl -s -o /dev/null -w '%header{Mcp-Session-Id}' -X POST http://localhost:18080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')

curl -s -X POST http://localhost:18080/mcp -H 'Content-Type: application/json' \
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
6. 输入 `各状态用户的数量分布` → 智能体自动出图：结果卡片标注「AI 图表」并以柱状图展示，可切换饼图 / 折线图与表格视图
7. 用两个不同浏览器打开工作台：各自会话列表互不可见；同一浏览器刷新 / 重启后历史会话仍在（浏览器指纹隔离）

## 凭据安全（大模型隔离）

- **数据库账号 / 密码 / 主机不发给大模型**：模型上下文与 MCP 响应中只有数据源名称 / 类型、表结构与 SQL 执行结果；工具异常消息返回前会统一脱敏（用户名、主机、密码、JDBC URL → `***`），防止凭据经工具结果回流。
- 数据源密码与模型 API Key 均以 AES-GCM 加密落库，接口与页面均不回显。
- `src/main/resources/sql/update.sql` 中的测试数据源（`192.168.110.88`）**仅用于本地开发与验收演示**，仓库不包含任何大模型 API Key；部署到真实环境前请替换为自有数据源并更换 `app.crypto.key`。

## 开发指南

```bash
# 仅后端迭代（跳过前端构建，仍会复制已有 dist）
./mvnw spring-boot:run -Pskip-frontend

# 前端热更（Vite dev server 已配置 /api、/ws 代理到 18080）
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
│   ├── chat/         会话域（WS 编排 / 确认 / 历史 / 指纹隔离 / REST）
│   └── common/       统一返回 / 全局异常 / AES-GCM 加解密 / 敏感信息脱敏 / 浏览器指纹
├── src/main/resources/
│   ├── application.yml
│   ├── logback-spring.xml   日志（控制台 + 滚动文件 logs/）
│   └── sql/          schema.sql（建表）· update.sql（幂等种子）
├── frontend/         Vue3 SPA（构建产物自动复制进 jar 的 static/）
├── logs/             运行日志（自动创建，已 gitignore）
└── docs/superpowers/ 设计文档（specs）与实施计划（plans）
```

## License

[Apache-2.0](LICENSE)

