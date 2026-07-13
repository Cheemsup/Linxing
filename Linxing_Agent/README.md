# Linxing_Agent

Linxing 平台的 Java 后端服务。基于自研 ReAct Agent 主循环与 `langchain4j-agentic` 多 Agent 工作流，在个人笔记知识库（Node-Based RAG）之上提供智能问答、知识测验出题、学习计划生成与联网搜索能力。

> 本 README 仅介绍当前服务。项目整体架构见根目录 [README.md](../README.md) 与 [AGENTS.md](../AGENTS.md)。

## 服务简介（Overview）

**职责**：业务编排与知识检索核心。承接前端请求，负责用户认证、文档入库编排（调用 Python 解析服务 → 语义增强 → Chunk 装箱 → 向量化与全文索引）、混合检索，以及 Agent 对话/测验/学习计划的全部业务逻辑。

**在系统中的位置**：

```
webconsole (Vue) ──/api 代理剥离──▶ Linxing_Agent (8080)
                                        │
                   ┌────────────────────┼────────────────────┐
                   ▼                    ▼                    ▼
        document_analysis_service   PostgreSQL/pgvector        Redis
              (8000, /parse)        chunks/embeddings       会话/预览/步骤缓存
```

**为什么存在**：把"检索个人笔记"封装成 Agent 可调用的工具，让对话、出题、学习计划生成等学习场景都建立在用户自己的笔记之上。Python 服务只负责文档结构化解析，向量存储、检索、Agent 编排、业务持久化均由本服务承担。

## 核心功能（Features）

- **自研 ReAct Agent 主循环**：上限 20 轮推理-工具调用-观察循环，SSE 流式推送每一步事件
- **多 Agent 工作流**：基于 `langchain4j-agentic` 的两阶段顺序编排（知识收集 → 内容生成），支持 HumanInTheLoop 澄清打断
- **Node-Based RAG 入库**：消费 Python 解析的 Node JSON，完成 VLM/LLM 语义增强、父子 Chunk 装箱、向量化与全文索引
- **Display / Index 文本双轨**：`chunkText`（展示，图片/代码/表格为占位符）服务前端渲染，`indexText`（含语义增强结果）服务检索
- **混合检索**：向量召回 + BM25 全文召回 + RRF 融合 + ONNX cross-encoder 重排序
- **渐进披露**：工具+技能数超过阈值（默认 5）时，LLM 仅看到 `resolve` 元工具，按需动态注入
- **技能系统**：基于 `SKILL.md`（YAML frontmatter）声明式技能，按需加载
- **Agent 记忆**：滑动窗口记忆 + 摘要压缩记忆（超 token 预算时按工具调用组为原子单位压缩）
- **多 LLM 供应商管理**：注册中心统一管理 MiniMax / DeepSeek / GLM / Kimi 等，均走 OpenAI 兼容 API
- **JWT 认证 + 多租户**：所有业务表带 `user_id`，拦截器统一鉴权

## 技术栈（Tech Stack）

| 技术 | 用途 |
|---|---|
| Spring Boot 4.0.5 / JDK 17 | Web 框架与运行时 |
| langchain4j 1.13.0 | RAG / Embedding / OpenAI 兼容 LLM 客户端 |
| langchain4j-agentic | 多 Agent 工作流（`@Agent` / `sequenceBuilder` / `humanInTheLoopBuilder`） |
| langchain4j-embeddings-bge-small-zh-v15 | 本地嵌入模型（暂用） |
| langchain4j-pgvector | 向量存储 |
| langchain4j-onnx-scoring | Cross-encoder 重排序（ms-marco-MiniLM-L-6-v2，暂用） |
| langchain4j-web-search-engine-tavily 1.13.0-beta23 | 联网搜索 |
| MyBatis 4.0.0 + Druid 1.2.28 | ORM（XML mapper）与连接池 |
| PostgreSQL 42.7.4 + pgvector 0.1.6 | 主库与向量库 |
| Spring Data Redis (Lettuce) | 会话消息 / 文档预览 / Agent 步骤缓存 |
| Caffeine | 技能指令 LRU 缓存 |
| jjwt 0.12.6 | JWT 认证 |
| onnxruntime 1.20.0 | 本地重排序推理 |
| pdfbox 3.0.1 / jsoup 1.18.3 / jieba-analysis 1.0.2 | 旧解析路径与中文分词（BM25） |

## 项目结构（Project Structure）

```
Linxing_Agent/
├── src/main/java/org/linxing/linxing_agent/
│   ├── common/                 # 共享基础设施
│   │   ├── config/             # RedisConfig / WebMvcConfig / LlmManager / JwtProperties
│   │   ├── constant/           # LlmType 等公共常量
│   │   ├── exception/          # GlobalExceptionHandler 全局异常
│   │   ├── interceptor/        # JwtTokenUserInterceptor
│   │   ├── result/             # Result / PageResult 统一返回
│   │   ├── security/           # JwtUtil / PasswordEncoder
│   │   └── userInfoMaintainer/ # BaseContext / UserInfo（ThreadLocal 请求上下文）
│   ├── user/                   # 用户认证域
│   │   ├── controller/         # UserController（/user）
│   │   ├── service/            # IUserService + impl
│   │   ├── entity/ dto/ vo/    # User 实体与出入参
│   │   ├── mapper/             # UserMapper
│   │   └── exception/          # 账号异常
│   ├── rag/                    # 知识检索域（Node-Based RAG）
│   │   ├── parse/              # DocumentAnalysisFacade / Python 调用 / NodeConverter
│   │   ├── enhancement/        # VLM/LLM 语义增强 + 上下文打包
│   │   ├── chunk/              # NodeBasedChunkBuilder（Node 装箱为父子 Chunk）
│   │   ├── pipeline/           # ChunkIngestCoordinator + handler/ 责任链
│   │   ├── service/            # SearchServiceImpl 混合检索
│   │   ├── controller/         # Ingest/Search/Chunk/Document Controller（/rag）
│   │   ├── entity/ dto/ vo/    # Chunk / NodeDTO / 检索结果
│   │   ├── node/               # DocumentNode 接口与各类型实现
│   │   ├── utils/              # Reranker / ReciprocalRankFusion / VectorUtils
│   │   ├── config/             # RagProperties / LangChain4jConfig
│   │   ├── strategy/           # ⚠️ 已整体废弃（旧按文件类型分派路径）
│   │   └── render/             # ⚠️ 已整体废弃（渲染逻辑已内联到 ChunkBuilder）
│   └── agent/                  # Agent 编排核心
│       ├── core/               # AgentExecutor ReAct 主循环 / 上下文 / 提示词 / SSE / 超时 / HumanInTheLoop
│       ├── adapter/            # SseChatAdapter 流式响应适配器
│       ├── tool/               # ToolRegistry + impl/ 工具 + jsoncontainer/ 容器工具
│       ├── skill/              # SkillRegistry + skills/ 技能定义
│       ├── catalog/            # CatalogProvider 渐进披露目录
│       ├── memory/             # WindowMemory / SummaryMemory
│       ├── subagent/           # 启用langchain4j的Agent功能的构建包，目前有study_plan的工作流agent
│       ├── controller/         # Chat/Exam/StudyPlan Controller
│       ├── entity/ dto/ vo/ mapper/   # 会话/步骤/测验/学习计划持久化
│       └── handler/            # JsonbTypeHandler / JsonListTypeHandler
├── src/main/resources/
│   ├── application.yaml        # 主配置（环境变量占位）
│   ├── application-dev.yaml    # ⚠️ 本地必需，被 gitignore（含密钥）
│   ├── schema.sql              # 14 张表 DDL（含 pgvector）
│   ├── mapper/{rag,user,agent}/ # MyBatis XML
│   ├── models/ms-marco-MiniLM-L-6-v2/  # ONNX 重排序模型 + tokenizer
│   └── skills/{exam,study-plan,_shared}/  # Agent系统内部使用的技能指令
├── src/test/java/              # 测试（Agent/RAG/工具/策略）
├── mvnw / mvnw.cmd             # Maven Wrapper
└── pom.xml
```

## 系统职责（Responsibilities）

**本服务负责**：

- 用户注册 / 登录 / 登出与 JWT 签发校验
- 文档上传与入库编排：调用 Python `/parse`、语义增强、Chunk 装箱、向量化、全文索引、持久化
- 混合检索：向量召回 + BM25 + RRF 融合 + ONNX 重排序
- Agent 对话：ReAct 主循环、工具调度、技能加载、记忆管理、SSE 流式推送
- 多 Agent 工作流：study_plan 两阶段编排 + HumanInTheLoop 澄清
- 业务持久化：会话/消息/推理步骤/测验/学习计划及其阶段与进度
- 多 LLM 供应商统一管理与 Redis 多级缓存（会话消息 / 文档预览 / Agent 步骤）

**本服务不负责**：

- 文档结构化解析（由 `document_analysis_service` 承担，本服务仅 HTTP 调用）
- PDF/DOCX 文本与图片抽取、OCR（Python 侧完成）
- 前端渲染与交互（由 `webconsole` 承担）

## 服务边界（Service Boundary）

| 维度 | 说明 |
|---|---|
| **输入** | 前端 HTTP 请求（JWT 鉴权）、Python 服务返回的 Node JSON 列表 |
| **输出** | REST JSON 响应、SSE 流式事件、持久化的 chunks/embeddings/会话/测验/学习计划 |
| **调用方** | `webconsole` 前端（经 `/api` 代理） |
| **被调用方** | `document_analysis_service`（`/parse`）、PostgreSQL、Redis、各 LLM 供应商 OpenAI 兼容 API、Tavily 搜索 API |

## 与其它服务协作（Integration）

### 调用 document_analysis_service

- 触发点：文档上传 `/rag/ingest/file` → `IngestServiceImpl` → `DocumentAnalysisFacade.analyze`
- `DocumentAnalysisFacade` 优先 `PythonDocumentAnalysisServiceImpl`（Spring `RestClient`，`multipart/form-data` POST `/parse`，连接超时 10s，读取超时 `rag.python-service.timeout-seconds` 默认 120s）
- 请求体含 `file`、`documentId`、`userId`，Python 侧据此把图片落到 `{storePath}/chunk_images/{userId}/{documentId}/`
- fallback 到 `JavaDocumentAnalysisServiceImpl`，但**该备用方案当前未实现，调用直接抛 `UnsupportedOperationException`** —— 开发时务必先启动 Python 服务

### 数据流转

```
前端上传文件
   │
   ▼
IngestController ──▶ IngestServiceImpl
   │
   ▼
DocumentAnalysisFacade ──HTTP /parse──▶ document_analysis_service
   │                                          │
   │   ◀──── Node JSON 列表 ──────────────────┘
   ▼
SemanticEnhancementService   (VLM 图片描述 / LLM 代码解释 / LLM 表格总结)
   │
   ▼
NodeBasedChunkBuilder        (父子 Chunk 装箱, 同时生成 chunkText + indexText)
   │
   ▼
ChunkIngestCoordinator       (两 pass 插入 → 责任链: 标题提取 / ts_content / 向量化)
   │
   ▼
PostgreSQL: chunks + embeddings(tsvector + vector(512))

对话检索:
AgentExecutor ──▶ search_knowledge_base 工具 ──▶ SearchServiceImpl
   │   向量召回(embeddings) + BM25(chunks.ts_content) + RRF + ONNX 重排序
   ▼
结果注入 Agent 上下文 → LLM 生成 → SSE 推送
```

### 前端代理

`vue.config.js` 把 `^/api` 重写为 `''`。前端调用 `/api/agent/chat`，后端收到 `/agent/chat`。**后端路径无 `/api` 前缀**，新增接口时匹配后端裸路径。

## 配置说明（Configuration）

配置文件位于 [src/main/resources/application.yaml](src/main/resources/application.yaml)，`spring.profiles.active=dev`，运行时合并本地 `application-dev.yaml`。

### 数据库

PostgreSQL（默认库名 `vectordb`）需安装 pgvector 扩展。schema 见 [schema.sql](src/main/resources/schema.sql)

### LLM 用途映射（`common/constant/LlmType`）

| 常量 | 默认 provider | 用途 |
|---|---|---|
| `CHAT_MODEL` | deepseek | Agent 主对话 |
| `VISION_MODEL` | other1 | 图片语义增强（VLM） |
| `CODE_ENHANCE_MODEL` | deepseek | 代码 Node 语义增强 |
| `TABLE_ENHANCE_MODEL` | deepseek | 表格 Node 语义增强 |
| `SUMMARY_MODEL` | deepseek | SummaryMemory 摘要压缩 |
| `SEMANTIC_CHUNK_MODEL` | glm | （旧路径，Node 体系下未使用） |
| `CONTEXT_ENRICH_MODEL` / `QUERY_REWRITE` | deepseek / minimax | （旧路径，已废弃） |

### 静态资源与文件存储

`WebMvcConfig.addResourceHandlers` 暴露 `/chunk_images/**`，物理目录优先 `rag.python-service.image-store-dir`，回退 `rag.store-path/chunk_images`。

## 快速启动（Quick Start）

### 环境要求

- JDK 17+
- Maven 3.6+（仓库内置 `mvnw` / `mvnw.cmd`）
- PostgreSQL 14+ 且已安装 pgvector 扩展
- Redis 6+
- `document_analysis_service` 已启动（默认 `http://localhost:8000`）

### 1. 准备数据库

创建数据库并启用 pgvector，执行 [schema.sql](src/main/resources/schema.sql)。

### 2. 创建 application-dev.yaml

在 [src/main/resources/](src/main/resources/) 下创建 `application-dev.yaml`，填入上文环境变量对应的真实值。该文件被 `.gitignore` 忽略，本地必需。

### 3. 启动 Python 解析服务（前置依赖）

```bash
cd ../document_analysis_service
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000 / npm run serve
```

### 4. 启动后端

```bash
./mvnw spring-boot:run / mvn spring-boot:run
```

## API

所有 Controller 均为 `@RestController`，路径无 `/api` 前缀。除 `/user/login`、`/user/register`、`/chunk_images/**` 外均需携带 Bearer Token。

### user 域

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/register` | 注册 |
| POST | `/user/login` | 登录，返回 JWT |
| POST | `/user/logout` | 登出（仅日志，无 token 黑名单） |

### rag 域

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rag/ingest/file` | 上传文档（multipart） |
| POST | `/rag/search` | 知识库检索 |
| GET | `/rag/chunks/{id}/context` | 分块上下文 |
| GET | `/rag/documents` | 文档列表（分页） |
| GET/DELETE | `/rag/documents/{id}` | 文档详情/删除 |
| GET | `/rag/documents/{id}/preview` | 文档预览 |
| GET | `/rag/documents/{id}/download` | 文档下载 |

### agent 域

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/agent/chat` | Agent 对话（SSE 流式） |
| POST | `/agent/workflow/clarify` | 工作流澄清回复（HumanInTheLoop 唤醒） |
| POST/GET/DELETE | `/agent/sessions[...]` | 会话增删查 |
| PUT | `/agent/sessions/{id}/title` | 重命名会话 |
| POST | `/agent/sessions/{id}/auto-title` | AI 自动命名 |
| GET | `/agent/sessions/{id}/messages` | 会话消息列表 |
| GET | `/agent/messages/{id}/steps` | 推理步骤（懒加载） |
| DELETE | `/agent/messages/{id}/subtree` | 删除消息子树 |
| GET | `/exam` `/exam/{id}` | 测验列表与详情 |
| POST | `/exam/{id}/submit` | 提交答题 |
| POST/GET | `/exam/{id}/draft` | 草稿保存/读取 |
| GET | `/exam/by-plan/{planId}` | 按学习计划查测验 |
| GET | `/study-plan` `/study-plan/{id}` | 学习计划列表与详情 |
| PUT | `/study-plan/{id}/phase/{phaseId}/progress` | 更新阶段进度 |
| GET | `/study-plan/{id}/export` | 导出计划（`format=md`/`html`） |

## 数据模型（Data Model）

14 张表（见 [schema.sql](src/main/resources/schema.sql)），全部带 `user_id` 实现多租户。

### 主要表

| 表 | 职责 |
|---|---|
| `users` | 用户（username / password_hash） |
| `documents` | 文档元信息（文件名/路径/状态/类型） |
| `chunks` | 文档切片，支持 Small-to-Big 父子结构与全文检索 |
| `embeddings` | pgvector 向量存储，关联 chunks |
| `activity_logs` | 用户操作审计（upload/query/delete） |
| `chat_sessions` / `chat_messages` | 对话会话与消息（`parent_id` 构成消息树） |
| `agent_steps` | ReAct 推理步骤记录（final 步骤不入库） |
| `exams` / `exam_context` / `exam_answers` | 测验元信息/题目上下文/用户答题 |
| `study_plans` / `study_plan_phases` / `study_plan_progress` | 学习计划/阶段/进度 |

### chunks 表关键字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `chunk_text` | TEXT NOT NULL | 展示文本（Display，原文/占位符） |
| `ts_content` | TSVECTOR | 全文检索列，由 `chunk_text` 经分词生成 |
| `parent_chunk_id` | INT | 父块 ID（Small-to-Big） |
| `chunk_level` | SMALLINT | 1=父块（不可检索），2=子块（可检索） |
| `node_metadata` | JSONB | Rich Node 元数据数组（图片/代码/表格/公式） |
| `is_searchable` | BOOLEAN | 是否参与检索（由 `chunk_level` 决定） |

> ⚠️ 最终落库的chunk_text并不是经过语义增强后的文本（Index），而是文档的原内容

### embeddings 表

`embedding` 列为 pgvector `vector` 类型，维度 512 由应用层 `::vector(512)` 强制（SQL 中转换，无自定义 TypeHandler）。距离用余弦距离 `<=>`，`score = 1 - 距离`。

### 主要关系

- `chunks.parent_chunk_id → chunks.id`（自引用，父子结构）
- `chunks.document_id → documents.id`
- `embeddings.chunk_id → chunks.id`
- `chat_messages.parent_id → chat_messages.id`（消息树）
- `exams.linked_plan_id → study_plans.id`（`ON DELETE SET NULL`）
- `study_plan_phases.plan_id → study_plans.id`，`study_plan_progress.phase_id → study_plan_phases.id`

## 开发说明（Development）

### 分层与模块划分

按 DDD 风格分层，依赖方向 `common → user → rag → agent`：

- **common**：共享基础设施。`LlmManager` 按 `LlmType` 统一获取各用途 LLM；`JwtTokenUserInterceptor` 拦截必要路径，校验后将 `UserInfo` 存入 `BaseContext`（ThreadLocal）；`GlobalExceptionHandler` 统一异常到 `Result`。
- **user**：JWT 认证。
- **rag**：知识检索基础设施，被 `agent` 域封装为 `search_knowledge_base` 工具。
- **agent**：业务编排核心。

### 各层职责

| 层 | 职责 | 代表类 |
|---|---|---|
| Controller | HTTP 端点 | `ChatController` / `IngestController` / `UserController` |
| Service | 业务编排 | `ChatServiceImpl` / `SearchServiceImpl` / `UserServiceImpl` |
| Coordinator/Pipeline | 入库责任链 | `ChunkIngestCoordinator` + `pipeline/handler/` |
| Mapper | 数据访问 | `ChunkMapper` / `EmbeddingMapper`（XML 在 `resources/mapper/`） |
| Config | 配置绑定 | `RagProperties` / `LlmManager` / `WebMvcConfig` / `RedisConfig` |
| Common | 跨域共享 | `Result` / `BaseContext` / `JwtUtil` / `LlmType` |
| Domain | 领域模型 | `DocumentNode` 接口与 `TextNode`/`ImageNode`/`CodeNode` 等 |
| DTO/VO | 出入参 | `NodeDTO` / `ChatRequest` / `UserLoginVO` |
| Exception | 异常 | `GlobalExceptionHandler` + 各域业务异常 |
| Utils | 工具 | `Reranker` / `ReciprocalRankFusion` / `VectorUtils` / `KeywordExtractor` |
| Handler | TypeHandler | `JsonbTypeHandler` / `JsonListTypeHandler`（JSONB ↔ Map/List） |

### Agent 域关键入口

| 组件 | 职责 |
|---|---|
| `agent/core/AgentExecutor` | ReAct 主循环（`MAX_STEPS=20`），推理→工具调用→观察→注入记忆 |
| `agent/core/AgentPrompts` | 全量 / 渐进披露两套 system prompt 模板 |
| `agent/core/StepRecorder` | 统一步骤记录（SSE 推送 + DB 持久化，final 步骤不入库） |
| `agent/core/ToolExecutionTimeout` | 分段计时 watchdog（HumanInTheLoop 等待期间 `pause()` 不扣预算） |
| `agent/core/HumanInTheLoopFactory` + `PendingClarificationRegistry` | 澄清阻塞 future 创建与超时自清理 |
| `agent/core/JsonContainer` | 分批构建复杂 JSON 的结构化输出机制 |
| `agent/adapter/SseChatAdapter` | SSE 流式（超时 30 分钟，事件名 `step`/`stream`/`result`/`done`/`error`） |
| `agent/tool/ToolRegistry` | `ContextRefreshedEvent` 自动发现 Tool Bean，生成 `ToolSpecification` |
| `agent/skill/SkillRegistry` | 扫描 `SKILL.md`，三阶段加载，Caffeine LRU（ maxSize 50 / 30 分钟） |
| `agent/memory/AgentMemoryFactory` | 按 `agent.memory.type` 创建 WindowMemory / SummaryMemory |
| `agent/subagent/StudyPlanner` | study_plan 两阶段 `sequenceBuilder` 编排 |

### 工具与技能

**已注册工具（0712）**（`agent/tool/impl/`）：

| name | 职责 |
|---|---|
| `search_knowledge_base` | 检索个人笔记知识库 |
| `web_search` | Tavily 联网搜索 |
| `resolve` | 渐进披露下获取工具/技能完整定义 |
| `start_study_plan_workflow` | 启动学习计划工作流 |
| `save_study_plan` / `save_exam` | 持久化计划/测验（支持分批 container 模式） |
| `create_container` / `append_to_container` / `remove_from_container` / `replace_in_container` / `replace_container_metadata` | JSON 容器分批构建 |

**已注册技能（0712）**（`src/main/resources/skills/`）：

| 技能 | 关联工具 |
|---|---|
| `exam` | `search_knowledge_base` / `web_search` / `save_exam` + 容器工具 |
| `study_plan` | `search_knowledge_base` / `web_search` / `start_study_plan_workflow` |

### 新增模块方式

- **新增工具**：在 `agent/tool/impl/` 下实现 `Tool` 接口并标注 `@Component`，`ToolRegistry` 自动发现注册（跳过 `@Deprecated`）。工具名全局唯一，冲突会启动失败。
- **新增技能**：在 `src/main/resources/skills/{name}/` 下新建 `SKILL.md`（YAML frontmatter 含 `name`/`description`/`tool_names`），`SkillRegistry` 启动时扫描 frontmatter 入索引。
- **新增 Mapper**：在对应域 `mapper/` 包下建 `@Mapper` 接口，XML 放 `resources/mapper/{域}/`，`@MapperScan` 已覆盖三个域。
- **新增 Controller**：标注 `@RestController` + `@RequestMapping`，路径无 `/api` 前缀；若需放行，在 `WebMvcConfig.addInterceptors` 的 `excludePathPatterns` 补充。

### 构建与测试

```bash
./mvnw clean package     # 构建
./mvnw test               # 运行测试
```

测试目录 [src/test/java/](src/test/java/) 覆盖 Agent 执行（`AgentExecutorDisclosureTest`）、工具（`SaveExamToolTest`/`SaveStudyPlanToolTest`/`TavilyApiCompatibilityTest`）、RAG 流程（`NodeBasedRagFlowTest`/`FullDocumentContextTest`/`SemanticContextTest`）、LLM 接入（`LlmManagerTest`/`DeepSeekReasoningTest`）、旧切分策略（已废弃）。

### 日志规范

无 `logback.xml`，使用 Spring Boot 默认 Logback 配置，仅控制台输出。级别见 `application.yaml`：`root=INFO`、`org.linxing.linxing_agent=INFO`、`org.apache.ibatis.mapper=WARN`。

## 进一步阅读

- [AGENTS.md](../AGENTS.md) — 整体架构、关键约束、依赖详述
- [根目录 README.md](../README.md) — 项目总览
- [src/main/resources/schema.sql](src/main/resources/schema.sql) — 数据库 schema
- [src/main/resources/application.yaml](src/main/resources/application.yaml) — 完整配置
- [document_analysis_service/README.md](../document_analysis_service/README.md) — Python 解析服务
- [webconsole/README.md](../webconsole/README.md) — 前端服务
