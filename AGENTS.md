# AGENTS.md — Linxing (Agent-Driven Learning Platform)

## Project overview

Monorepo: Spring Boot 4.x backend (`Linxing_Agent/`) + Vue 3 frontend (`webconsole/`) + Python 文档解析服务 (`document_analysis_service/`)。
Agent-driven personal learning platform — 自研 ReAct Agent 主循环 + `langchain4j-agentic` 多 Agent 工作流，在 PG 向量知识库（Node-Based RAG）、联网搜索之上提供对话、学习计划生成、知识测验出题与联网搜索能力。

## Commands

```bash
# Backend (from Linxing_Agent/)
./mvnw spring-boot:run                    # start backend on :8080
./mvnw compile                            # compile-only

# Python 文档解析服务 (from document_analysis_service/)
uvicorn app:app --host 0.0.0.0 --port 8000   # Node-Based RAG 的解析入口，需先于后端启动
# 或 python app.py

# Frontend (from webconsole/)
yarn serve                                # dev server on :3000, proxies /api → :8080
yarn build                                # production build
yarn lint                                 # ESLint
```

## Architecture

- **Backend package**: `org.linxing.linxing_agent`
- **Domain-driven layout**: `common/`（共享基础设施）→ `user/`（认证）→ `rag/`（知识检索）→ `agent/`（对话编排、工具、技能、子 Agent 工作流）
- **Data access**: MyBatis XML mappers under `resources/mapper/{agent,rag}/`
- **Multi-tenant**: All tables carry `user_id`; JWT interceptor extracts user on every request
- **Auth**: JWT via `JwtTokenUserInterceptor` — excludes only `/user/login` and `/user/register`（后端路径无 `/api` 前缀）
- **Database**: PostgreSQL `vectordb` on localhost:5432，需 `pgvector` 扩展，schema 在 `schema.sql`

### Node-Based RAG 架构（rag 域核心）

文档入库统一走 Node 体系，旧 `ChunkStrategyFactory + strategy.execute` 按文件类型分派路径已废弃：

| 组件 | 职责 |
|---|---|
| `rag/parse/DocumentAnalysisFacade` | 解析门面：优先调 Python 服务，失败 fallback 到 Java（Java 备用方案当前未实现，调用会报错） |
| `rag/parse/PythonDocumentAnalysisServiceImpl` | 调用 `document_analysis_service` 的 `/parse`，反序列化为 `ParseResult` |
| `rag/parse/NodeConverter` | `NodeDTO` → `DocumentNode` 实现（按 type 分派到 Text/Heading/Image/Code/Table/Formula） |
| `rag/node/DocumentNode` | Node 统一接口：`originalContent()`（Display）/ `semanticText()`（Index）/ `metadata()`（含 titlePath、groupId） |
| `rag/enhancement/SemanticEnhancementService` | VLM/LLM 语义增强：IMAGE→VLM 描述、CODE→LLM 解释、TABLE→LLM 总结，打包临近上下文，带指数退避重试 |
| `rag/enhancement/SemanticContextBuilder` | 构造 `SemanticContext`（前 N + 后 N 邻居，默认各 2），失败 fallback 到默认 `semanticText` |
| `rag/chunk/NodeBasedChunkBuilder` | Node 装箱成 Chunk：普通 Node 按 token 装箱 → Level2；同 `groupId` 子 Node 合成 Level1 父块（不可检索）+ 多个 Level2 子块（`parentChunkId` 指向父块） |
| `rag/pipeline/ChunkIngestCoordinator` | 入库协调：语义增强 → ChunkBuilder → 两 pass 插入（先 Level1 父块建立 index→dbId 映射，再 Level2 子块解析 parentChunkId）→ 责任链后处理 |
| `rag/pipeline/handler/*` | 责任链 handler：`EmbeddingPersist`（向量化，优先 indexText）/ `FullTextIndexer`（BM25 全文索引）/ `TitlePathExtractor` / `ContextEnricher` / `ChunkTypeClassifier` / `SearchabilityMarker` |
| `rag/strategy/*` | 旧 ChunkStrategy 体系（已废弃，保留供历史参考，`ChunkStrategyFactory`/`ChunkStrategy`/`ChunkStrategyContext` 及各 impl 不再有调用方） |
| `rag/render/*` | 旧渲染器（已废弃，`@Deprecated`；Display/Index 双轨已内联到 `NodeBasedChunkBuilder.buildChunkFromNodes`） |

### Display/Index 文本双轨

- **chunkText（Display）**：`originalContent()` 拼接，保留原文形态（图片/代码/表格为占位符 `[[LINXING:TYPE:nodeId]]`），前端通过 `nodeMetadata` 还原
- **indexText（Index）**：`semanticText()` 拼接，含 VLM/LLM 语义增强结果，供 Embedding + BM25 使用
- 下游 `EmbeddingPersist`/`FullTextIndexer` 优先读 `indexText`，缺失时回退 `chunkText`
- `render/` 包的三个类已标记废弃（见 `render/LISTEN.md`），渲染逻辑内联在 `NodeBasedChunkBuilder`

### Agent 域核心组件

| 组件 | 职责 |
|---|---|
| `agent/core/AgentExecutor` | 自研 ReAct 主循环（上限 20 步），LLM 推理 → 工具调用 → 结果注入 → 下一轮 |
| `agent/core/AgentContext` / `AgentResult` / `AgentStepEvent` | 执行上下文、结果、SSE 步骤事件 |
| `agent/core/AgentPrompts` | 系统提示词模板（全量 / 渐进披露两套） |
| `agent/core/JsonContainer` + `tool/impl/jsoncontainer/*` | JSON 容器工具（create/append/replace/remove/replace-metadata），用于结构化输出 |
| `agent/core/ToolExecutionTimeout` | 工具超时控制（普通 180s，工作流 600s） |
| `agent/adapter/SseChatAdapter` | SSE 流式响应适配器（超时 30 分钟，覆盖澄清等待） |

### 工具与技能注册中心

- `agent/tool/ToolRegistry` — Spring 自动发现所有 `Tool` Bean 并注册，生成 LangChain4j `ToolSpecification`
- `agent/skill/SkillRegistry` — 扫描 `skills/` 目录下的 `SKILL.md`（YAML frontmatter），三阶段加载：
  - Phase 1：启动时全量解析 frontmatter 到内存
  - Phase 2：正文按需从磁盘读取，Caffeine LRU 缓存（30 分钟）
  - Phase 3：`references/` 与 `assets/` 资源文件按需读取，不缓存
- `agent/catalog/CatalogProvider` — `ToolRegistry` 与 `SkillRegistry` 共同实现，统一渲染为系统提示词中的「可用能力」目录
- **渐进披露模式**：当工具 + 技能总数超过 `agent.disclosure.threshold`（默认 5）时，LLM 仅看到 `resolve` 元工具；调用 `resolve` 后对应工具规格动态注入下一轮

### 当前注册的 12 个工具

| 工具 | 说明 |
|---|---|
| `search_knowledge_base` | 检索用户知识库（封装 RAG 检索） |
| `web_search` | Tavily 联网搜索 |
| `start_study_plan_workflow` | 触发 study_plan 多 Agent 工作流（工作流超时 600s） |
| `save_study_plan` / `save_exam` | 持久化计划 / 测验 |
| `resolve` / `catalog` | 渐进披露元工具 |
| `create_container` / `append_to_container` / `replace_in_container` / `remove_from_container` / `replace_container_metadata` | JSON 容器操作（结构化输出） |

### 当前技能（位于 `src/main/resources/skills/`）

- `study_plan` — 学习计划制定（关联工具：search_knowledge_base / web_search / start_study_plan_workflow）
- `exam` — 知识测验出题（含 `references/question-types.md` 资源）
- `_shared/references/batch-json-pattern.md` — 共享资源

### 多 Agent 工作流（`agent/subagent/`，基于 `langchain4j-agentic`）

`study_plan` 工作流采用两阶段顺序编排（`sequenceBuilder`）：

1. **知识收集**：`KnowledgeCollectionWorkflowService` + `KnowledgeCollectorAgent` —— 可选 HumanInTheLoop 澄清 + 自主调用搜索工具收集素材，写入 `AgenticScope.materials`
2. **内容生成**：`ContentGenerationWorkflowService` + `PlanGeneratorAgent`（+ 条件 `ExamGeneratorAgent`）—— 基于素材生成计划 JSON / 测验 JSON，校验后持久化

关键类：`StudyPlanWorkflowAgent`（`@Agent` 接口）、`SubAgentContext`（线程上下文，对 LLM 不可见）、`PendingClarificationRegistry`（HumanInTheLoop 唤醒）、`JsonSanitizer`、`KnowledgeSearchTools`。

### Agent 记忆（`agent/memory/`）

- `AgentMemory` 接口 + `AgentMemoryFactory`（按 `agent.memory.type` 创建）
- `WindowMemory` — 滑动窗口（默认 40 条），系统提示词独立存储
- `SummaryMemory` — 继承 `WindowMemory`，超过 token 预算（默认 32000）时以「工具调用组」为原子单位摘要压缩

## Critical gotchas

### 前端代理剥离 `/api` 前缀
`vue.config.js` 重写 `^/api` → `''`。前端调用 `/api/agent/chat`，后端收到的是 `/agent/chat`。新增接口时匹配后端路径（无 `/api` 前缀）。

### Node 体系是唯一入库路径
所有文件类型统一走 `IngestServiceImpl` → `DocumentAnalysisFacade.analyze` → `ChunkIngestCoordinator.processDocumentFromNodes`。旧 `ChunkIngestCoordinator.processDocument`（基于 `ChunkStrategyFactory` 按文件类型分派）已 `@Deprecated`，调用直接抛 `UnsupportedOperationException`，保留仅供历史参考。`rag/strategy/` 与 `rag/render/` 包整体废弃。

### Python 服务需先于后端启动
Node-Based RAG 的文档解析依赖 `document_analysis_service`（默认 `http://localhost:8000`）。`DocumentAnalysisFacade` 失败时会 fallback 到 `JavaDocumentAnalysisServiceImpl`，但 Java 备用方案**当前尚未实现**，调用会报错（见 `DocumentAnalysisFacade` 的 TODO）。开发时务必先启动 Python 服务。

### 语义增强结果必须进入 indexText
Node-Based 架构的核心价值在于 VLM/LLM 语义增强提升检索质量。`NodeBasedChunkBuilder.buildChunkFromNodes` 同时生成 `chunkText`（Display，原文/占位符）与 `indexText`（Index，含语义增强结果）。下游 `EmbeddingPersist`/`FullTextIndexer` 优先读 `indexText`，缺失才回退 `chunkText`。新增检索相关 Handler 时必须沿用此优先级，否则语义增强会变成空转。

### 后端路径与拦截器
JWT 拦截器 `addPathPatterns("/**")`，仅排除 `/user/login` 与 `/user/register`。所有 `/agent/**`、`/exam/**`、`/study-plan/**`、`/rag/**` 接口均需携带 Bearer Token。

### `application-dev.yaml` 被忽略但必需
包含 DB 密码、各 LLM API key、`TAVILY_API_KEY`、`JWT_SECRET_KEY`、模型路径。本地必须存在。非密钥配置项可见于 `application.yaml`。

### 模型文件与文件存储被忽略
`models/`（ONNX reranker 模型 `ms-marco-MiniLM-L-6-v2`）与 `files_store/`（上传文档 + Python 解析出的图片 `chunk_images/`）在 `.gitignore` 中。本地路径在 `application-dev.yaml` 配置（`rag.reranker.model-path`、`rag.store-path`）；Python 侧 `IMAGE_STORE_DIR` 默认指向 `D:/JavaProjects/Linxing/files_store/chunk_images`，应与 `rag.store-path/chunk_images` 一致。

### 技能目录位置
默认从 classpath 解析，开发环境指向 `target/classes/skills/`，对应源码 `src/main/resources/skills/`。如需指向外部目录，配置 `agent.skills.path`。每个技能一个子目录，内含 `SKILL.md` + 可选 `references/`、`assets/`。

### LLM 配置
通过 `rag.llm.default-provider` 选择（`minimax` / `deepseek` / `glm` / `kimi`），均走 OpenAI 兼容 API。DeepSeek 支持 thinking tokens（`return-thinking: true`、`send-thinking`）。`LlmManager` 统一管理 `CHAT_MODEL` 类型。

`common/constant/LlmType` 定义各用途的模型 provider 常量：
- `CHAT_MODEL`（deepseek）— Agent 主对话
- `VISION_MODEL`（other1）— VLM 图片描述（语义增强）
- `CODE_ENHANCE_MODEL` / `TABLE_ENHANCE_MODEL`（deepseek）— 语义增强
- `QUERY_REWRITE`（minimax）— 查询改写
- `CONTEXT_ENRICH_MODEL`（deepseek）— 补全短 chunk 上下文
- `SUMMARY_MODEL`（deepseek）— 摘要记忆
- `SEMANTIC_CHUNK_MODEL`（glm）— 语义分块（旧路径，Node 体系下未使用）

### Python 文档解析服务
`document_analysis_service` 是 Node-Based RAG 的唯一解析入口，通过 `rag.python-service.url`（默认 `http://localhost:8000`）调用。需先于后端启动：
- `router.py` 按扩展名 + 内容特征二次判定类型，派发到 pdf/docx/markdown/html/code/linebased parser
- pdf/docx 单例懒加载并注入图片目录（`IMAGE_STORE_DIR`），避免未用时强制加载 fitz/pdfplumber/python-docx
- 图片直接保存到 Java 的 `storePath/chunk_images/{userId}/{docId}/`，Java 无需搬运
- 详见 [document_analysis_service/README.md](document_analysis_service/README.md)

### Redis 语义缓存
Redis 同时承担会话消息缓存（`RAG_CACHE_SESSION_MSGS_TTL`）与基于 Vector Set 的语义缓存（`RAG_SEMANTIC_CACHE_ENABLED`、`threshold`、`quantization`）。Jedis 客户端单独配置（版本 6.2.0）。

### ONNX runtime
重排序器使用 `langchain4j-onnx-scoring` + `ms-marco-MiniLM-L-6-v2`。ONNX 原生库由 Java 库自动下载，无需手动安装。

### SSE 超时与工作流澄清
`SseChatAdapter` 超时 30 分钟，需大于工作流澄清等待时长（25 分钟）。`start_study_plan_workflow` 工作流执行时间较长，单独放宽到 600s（`agent.tool.workflow-timeout-seconds`），其他工具默认 180s（`agent.tool.timeout-seconds`）。

### `agent_steps` 表不存储 final 步骤
ReAct 循环的最终回答仅写入 `chat_messages`，`agent_steps` 只记录 thinking / tool_call / tool_result / error。前端按消息 ID 懒加载步骤。

### Maven 显式声明源码目录
`pom.xml` 显式设置 `<sourceDirectory>src/main/java</sourceDirectory>` 与 `<testSourceDirectory>src/test/java</testSourceDirectory>`。这是默认值但被显式声明，勿改动。

### Vue CLI 5 + yarn
使用 `yarn` 不要用 `npm`，锁文件为 `yarn.lock`。

## Key dependencies

### 后端

| Library | Purpose |
|---|---|
| `langchain4j` 1.13.0 | 核心 RAG 框架 |
| `langchain4j-agentic` | 多 Agent 工作流（@Agent / sequenceBuilder / humanInTheLoopBuilder） |
| `langchain4j-web-search-engine-tavily` 1.13.0-beta23 | Tavily 联网搜索 |
| `langchain4j-embeddings-bge-small-zh-v15` | 本地嵌入模型（512 维） |
| `langchain4j-pgvector` | PG 向量存储 |
| `langchain4j-open-ai` | LLM 客户端（多供应商走 OpenAI 兼容 API） |
| `langchain4j-onnx-scoring` | Cross-encoder 重排序 |
| `mybatis-spring-boot-starter` 4.0.0 | ORM（XML mappers） |
| `druid-spring-boot-4-starter` 1.2.28 | 连接池 |
| `spring-boot-starter-data-redis` + `jedis` 6.2.0 | Redis 语义缓存 / 会话消息缓存 |
| `caffeine` | 技能指令 LRU 缓存 |
| `jjwt` 0.12.6 | JWT 认证 |
| `jsoup` 1.18.3 | HTML 解析（旧 HtmlChunkStrategy，Node 体系下未使用） |
| `jieba-analysis` 1.0.2 | 中文分词（BM25） |
| `pdfbox` 3.0.1 | PDF 解析（旧路径，Node 体系下 PDF 由 Python 服务解析） |

### Python 文档解析服务（`document_analysis_service/`）

| Library | Purpose |
|---|---|
| `fastapi` 0.115.6 + `uvicorn` 0.34.0 | Web 框架 |
| `PyMuPDF` (fitz) | PDF 文本/图片抽取 |
| `pdfplumber` 0.11.0 | PDF 表格抽取 |
| `python-docx` 1.1.0 | DOCX 解析 |
| `mistune` 3.3.2+ | Markdown 结构识别 |
| `beautifulsoup4` 4.15.0 | HTML DOM 遍历 |
| `Pillow` 10.0.0+ | 图片处理 |
