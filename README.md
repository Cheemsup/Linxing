# Linxing

Agent 驱动的个人学习平台。基于自研 ReAct Agent 主循环与多 Agent 工作流，在个人笔记知识库（Node-Based RAG）之上提供智能问答、学习计划生成、知识测验出题与联网搜索能力。

## 为什么创建这个项目

个人学习材料（PDF / DOCX / Markdown / 笔记 / 代码片段）分散、难检索、难复习。Linxing 把这些材料统一入库为可检索的知识库，再用 Agent 把"检索"封装成一个可被 LLM 调用的工具，从而让对话、出题、生成学习计划等学习场景都建立在**用户自己的笔记**之上，而非通用语料。

## 适用场景

- 把课程笔记 / 技术文档 / 代码整理成个人知识库，并通过对话检索和使用
- 基于知识库自动生成学习计划与知识测验
- 在学习中联网补充外部资料并与笔记内容对照

## 核心特性

- **自写 ReAct Agent 主循环**：有上限推理-工具调用-观察循环，SSE 流式推送每一步事件
- **多 Agent 工作流**：基于 `langchain4j-agentic` 的两阶段编排（集成本地RAG和网络搜索MCP服务知识收集 → 内容生成），支持 HumanInTheLoop 打断补充
- **Node-Based RAG**：Python 服务统一解析所有文件类型为原子化 Node，Java 侧完成语义增强、父子 Chunk 装箱与向量化。构建Display / Index 文本双轨——展示文本保留原文形态（图片/代码/表格为占位符），索引文本含 VLM/LLM 语义增强结果，分别服务前端渲染与检索
- **混合检索**：向量召回 + BM25 全文召回 + RRF 融合 + ONNX cross-encoder 重排序
- **渐进披露**：工具/技能数量超过阈值时，LLM 仅看到 元工具，按需动态注入工具规格
- **技能系统**：基于 `SKILL.md`（YAML frontmatter）声明式技能，按需加载
- **Agent 记忆**：滑动窗口记忆 + 摘要压缩记忆（超 token 预算时按工具调用组为原子单位压缩）
- **多 LLM 供应商管理**：注册中心管理MiniMax / DeepSeek / GLM / Kimi 等多个大模型配置

## 技术栈

| 技术 | 用途 |
|---|---|
| Spring Boot 4.0.5 / JDK 17 | 后端框架 |
| langchain4j 1.13.0 | RAG 框架 |
| langchain4j-agentic | 多 Agent 工作流编排 |
| langchain4j-embeddings-bge-small-zh-v15 | 本地嵌入模型（暂定） |
| langchain4j-pgvector | 向量存储 |
| langchain4j-onnx-scoring | Cross-encoder 重排序（ms-marco-MiniLM-L-6-v2，暂定） |
| langchain4j-web-search-engine-tavily | 联网搜索 |
| MyBatis 4.0.0 + Druid | ORM 与连接池 |
| PostgreSQL + pgvector | 主库与向量库 |
| Redis (Lettuce) | 会话消息 / 文档预览 / Agent 步骤缓存 |
| JWT (jjwt) | 认证 |
| Vue 3.2.13 + Element Plus 2.13.7 | 前端 |
| FastAPI 0.115.6 + Uvicorn | Python 文档解析服务 |
| PyMuPDF / pdfplumber / python-docx / mistune / beautifulsoup4 | 文档结构解析 |

## 项目结构

```
Linxing/
├── Linxing_Agent/              # Spring Boot 后端（org.linxing.linxing_agent）
│   └── src/main/java/org/linxing/linxing_agent/
│       ├── common/             # 共享基础设施：LlmManager / RedisConfig / JWT 拦截器
│       ├── user/               # 用户认证（注册 / 登录 / 登出）
│       ├── rag/                # 知识检索域
│       │   ├── parse/          # Python 服务对接、Node 反序列化
│       │   ├── enhancement/    # VLM/LLM 语义增强（图片/代码/表格）
│       │   ├── chunk/          # Node 装箱为 Chunk（父子关系）
│       │   ├── pipeline/       # 入库责任链协调器
│       │   ├── service/        # 混合检索、向量持久化、全文索引
│       │   └── controller/     # 文档上传 / 检索 / 分块上下文接口
│       └── agent/              # Agent 编排核心
│           ├── core/           # ReAct 主循环、上下文、提示词、SSE 事件
│           ├── adapter/        # SSE 流式响应适配器
│           ├── tool/           # 工具注册中心与各工具实现
│           ├── skill/          # 技能注册中心（SKILL.md 扫描）
│           ├── catalog/        # 渐进披露目录
│           ├── memory/         # 窗口记忆 / 摘要记忆
│           ├── subagent/       # 学习计划多 Agent 工作流
│           └── controller/     # 对话 / 测验 / 学习计划接口
├── document_analysis_service/  # Python FastAPI 文档解析服务
│   ├── app.py                  # FastAPI 入口，/parse 与 /health
│   ├── config.py               # 环境变量配置
│   └── parsers/                # 各文件类型解析器，统一产出 Node JSON
├── webconsole/                 # Vue 3 前端
│   └── src/
│       ├── api/                # 后端接口封装
│       ├── stores/             # 自封装状态管理
│       ├── composables/        # Markdown 渲染等组合式函数
│       ├── views/              # 页面级组件
│       ├── components/         # 可复用组件
│       └── router/             # 路由表与守卫
├── files_store/                # 文档与图片存储（gitignore）
├── reference/                  # 开发参考/计划
└── AGENTS.md                   # 架构与开发约束详述
```

## 架构概览

```
┌─────────────┐     SSE/HTTP      ┌──────────────────────────────┐
│  webconsole │ ─────────────────▶│      Linxing_Agent (8080)     │
│  (Vue 3)    │◀─────────────────│  ReAct Agent + 多 Agent 工作流 │
└─────────────┘   /api 前缀剥离   └──────────┬───────────────────┘
                                            │
                       ┌────────────────────┼────────────────────┐
                       ▼                    ▼                    ▼
              ┌────────────────┐   ┌─────────────────┐   ┌──────────────┐
              │ document_      │   │ PostgreSQL /    │   │   Redis      │
              │ analysis_      │   │ pgvector        │   │ 会话/预览缓存│
              │ service (8000) │   │ chunks/embeddings│  └──────────────┘
              └────────────────┘   └─────────────────┘
```

**文档入库数据流**：

1. 用户在前端上传文档 → 后端 `/rag/ingest/file`
2. Java 侧 `DocumentAnalysisFacade` 调用 Python `/parse`，得到 Node JSON 列表
3. `SemanticEnhancementService` 对图片（VLM）、代码（LLM）、表格（LLM）做语义增强
4. `NodeBasedChunkBuilder` 装箱 Chunk（包含父子关系构建），同时生成 `chunkText`（展示）与 `indexText`（索引）
5. `ChunkIngestCoordinator` 责任链完成向量化与全文索引，持久化到 PG

**对话数据流**：用户提问 → `AgentExecutor` ReAct 循环 → 必要时调用 `search_knowledge_base` 工具走混合检索 → 结果注入上下文 → LLM 生成回答 → SSE 推送步骤事件。

## 快速开始

### 环境要求

- JDK 17+
- Maven（仓库内置 `mvnw` / `mvnw.cmd`）
- Node.js 16+ 与 yarn
- Python 3.10+
- PostgreSQL 14+ 且**已安装 pgvector 扩展**
- Redis 6+

### 1. 准备数据库

创建数据库 `vectordb` 并启用 pgvector 扩展，schema 见 [Linxing_Agent/src/main/resources/schema.sql](Linxing_Agent/src/main/resources/schema.sql)。

### 2. 配置环境变量

后端 `application.yaml` 通过环境变量注入敏感信息。在 [Linxing_Agent/src/main/resources/](Linxing_Agent/src/main/resources/) 下创建 `application-dev.yaml`，填入：

- `PG_HOST` / `PG_PORT` / `PG_DATABASE` / `PG_USER` / `PG_PASSWORD`
- `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`
- `RAG_STORE_PATH`（文档与图片存储根目录，替代OSS服务）
- `LLM_DEFAULT_PROVIDER`对应大模型提供商的 `api-key` / `base-url` / `model`等
- `TAVILY_API_KEY`
- `JWT_SECRET_KEY` / `JWT_TTL` / `JWT_TOKEN_NAME`

### 3. 启动 Python 文档解析服务

Python 服务需**先于后端启动**（后端文档入库依赖它）。

```bash
cd document_analysis_service
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000 / python app.py
```

### 4. 启动后端

```bash
cd Linxing_Agent
./mvnw spring-boot:run / mvn spring-boot:run
```

### 5. 启动前端

```bash
cd webconsole
yarn install / npm install
yarn serve / npm run serve
```

### 访问地址

| 服务 | 地址 |
|---|---|
| 前端 | http://localhost:3000 |
| 后端 API | http://localhost:8080 |
| Python 解析服务 | http://localhost:8000 |
| Python 健康检查 | http://localhost:8000/health |

## 配置说明

以下为开发者常需调整的配置项，完整配置见 [Linxing_Agent/src/main/resources/application.yaml](Linxing_Agent/src/main/resources/application.yaml)。

### LLM 模型

通过 `rag.llm.default-provider` 切换供应商（`minimax` / `deepseek` / `glm` / `kimi` / `other1`），均走 OpenAI 兼容 API。DeepSeek 支持 `return-thinking` 与 `send-thinking`。

### Agent 行为

| 配置 | 默认值 | 说明 |
|---|---|---|
| `agent.disclosure.threshold` | 5 | 工具+技能超过此值启用渐进披露 |
| `agent.tool.timeout-seconds` | 180 | 普通工具超时 |
| `agent.tool.workflow-timeout-seconds` | 600 | 工作流工具超时 |
| `agent.memory.type` | window | 记忆类型（`window` / `summary`） |
| `agent.memory.max-messages` | 40 | 窗口记忆条数 |
| `agent.memory.max-tokens` | 32000 | 摘要记忆触发阈值 |

### 检索与缓存

| 配置 | 默认值 | 说明 |
|---|---|---|
| `rag.reranker.enabled` | true | 是否启用 ONNX 重排序 |
| `rag.reranker.batch-size` | 8 | 重排序批大小 |
| `rag.cache.session-messages-ttl` | 1800 | 会话消息缓存 TTL（秒） |
| `rag.cache.doc-preview-ttl` | 3600 | 文档预览缓存 TTL（秒） |
| `rag.cache.agent-steps-ttl` | 3600 | Agent 步骤缓存 TTL（秒） |
| `rag.semantic-enhancement.context.previous-nodes` | 2 | 语义增强前文 Node 数 |
| `rag.semantic-enhancement.context.next-nodes` | 2 | 语义增强后文 Node 数 |

### Python 服务对接

| 配置 | 默认值 | 说明 |
|---|---|---|
| `rag.python-service.url` | http://localhost:8000 | Python 解析服务地址 |
| `rag.python-service.timeout-seconds` | 120 | 调用超时 |
| `rag.python-service.image-store-dir` | ${RAG_STORE_PATH}/chunk_images | 图片落盘目录 |

Python 服务侧的环境变量见 [document_analysis_service/config.py](document_analysis_service/config.py)（`SERVICE_HOST` / `SERVICE_PORT` / `IMAGE_STORE_DIR` / `IMAGE_URL_PREFIX`）。

## 使用示例

### 上传笔记并以此生成知识测验

1. 启动三个服务后，浏览器访问 http://localhost:3000，注册并登录
2. 进入「导入笔记」页上传 PDF / DOCX / Markdown 等格式文件
3. 进入「智能问答」发起对话，Agent 会按需检索你的笔记库内容作为参考、启动工作流自动生成知识测验

### 调用检索接口

```bash
curl -X POST http://localhost:8080/rag/search \
  -H "Authorization: Bearer <your-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{"query": "向量检索原理", "topK": 5, "hybrid": true}'
```

### 上传文档接口

```bash
curl -X POST http://localhost:8080/rag/ingest/file \
  -H "Authorization: Bearer <your-jwt-token>" \
  -F "file=@/path/to/note.pdf"
```

## 主要 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/register` `/user/login` `/user/logout` | 用户认证 |
| POST | `/agent/chat` | Agent 对话（SSE 流式） |
| POST | `/agent/workflow/clarify` | 工作流澄清回复 |
| GET/POST/DELETE | `/agent/sessions[...]` | 会话管理 |
| GET | `/agent/messages/{id}/steps` | 查看推理步骤 |
| GET | `/exam` `/exam/{id}` | 测验列表与详情 |
| POST | `/exam/{id}/submit` | 提交答题 |
| GET | `/study-plan` `/study-plan/{id}` | 学习计划 |
| GET | `/study-plan/{id}/export?format=md` | 导出计划 |
| POST | `/rag/ingest/file` | 上传文档 |
| GET/DELETE | `/rag/documents[...]` | 文档管理 |
| POST | `/rag/search` | 知识库检索 |
| GET | `/rag/chunks/{id}/context` | 分块上下文 |

> 后端路径无 `/api` 前缀；前端调用统一加 `/api`，由 `vue.config.js` 代理剥离。所有非登录注册接口需携带 Bearer Token。

## 开发说明

### 模块划分

- 后端按 DDD 风格分层：`common` → `user` → `rag` → `agent`
- `rag` 域是知识检索基础设施，被 `agent` 域封装为 `search_knowledge_base` 工具
- `agent` 域是业务编排核心，包含对话、工具、技能、记忆、子 Agent 工作流

### 构建与测试

```bash
cd Linxing_Agent
./mvnw clean package        # 构建
./mvnw test                 # 运行测试
```

测试目录 [Linxing_Agent/src/test/java/](Linxing_Agent/src/test/java/) 包含 Agent 执行、工具、RAG 流程、语义上下文等测试。

### 前端构建

```bash
cd webconsole
yarn build                  # 生产构建
yarn lint                   # 代码检查
```


## 进一步阅读

- [AGENTS.md](AGENTS.md) — 架构、关键约束、依赖详述
- [document_analysis_service/README.md](document_analysis_service/README.md) — Python 解析服务文档
- [webconsole/README.md](webconsole/README.md) — 前端文档
- [Linxing_Agent/src/main/resources/schema.sql](Linxing_Agent/src/main/resources/schema.sql) — 数据库 schema

## 贡献

欢迎通过 Issue 反馈问题或提出功能建议。提交 PR 前：

1. Fork 本仓库
2. 新建分支开发
3. 确保后端 `mvnw test` 与前端 `yarn lint` 通过
4. PR 描述清楚变更目的与影响范围
