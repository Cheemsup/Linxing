# AGENTS.md — Linxing (Agent-Driven Learning Platform)

## Project overview

Monorepo: Spring Boot 4.x backend (`Linxing_Agent/`) + Vue 3 frontend (`webconsole/`) + Python 文档解析服务 (`document_analysis_service/`)。
Agent-driven personal learning platform — 自研 ReAct Agent 主循环 + `langchain4j-agentic` 多 Agent 工作流，在 PG 向量知识库（Node-Based RAG）、联网搜索之上提供对话、学习计划生成、知识测验出题与联网搜索能力。

## Architecture

- **Backend package**: `org.linxing.linxing_agent`
- **Domain-driven layout**: `common/`（共享基础设施）→ `user/`（认证）→ `rag/`（知识检索）→ `agent/`（对话编排、工具、技能、子 Agent 工作流）
- **Auth**: JWT via `JwtTokenUserInterceptor` — excludes only `/user/login` and `/user/register`（后端路径无 `/api` 前缀）

### Node-Based RAG 架构（rag 域核心）

主要的流程：文档给到python侧划分出Node数据（详见document_analysis_service\README.md），Node List给回java侧完成图片解读、语义丰富、拼装等（在保障Node原子性的情况下消费Node），以及完成后续的向量化chunk等

#### Display/Index 文本双轨

- **chunkText（Display）**：`originalContent()` 拼接，保留原文形态（图片/代码/表格为占位符 `[[LINXING:TYPE:nodeId]]`），前端通过 `nodeMetadata` 还原
- **indexText（Index）**：`semanticText()` 拼接，含 VLM/LLM 语义增强结果，供 Embedding + BM25 使用
- 下游 `EmbeddingPersist`/`FullTextIndexer` 优先读 `indexText`，缺失时回退 `chunkText`

### Agent 域核心组件（部分）

| 组件 | 职责 |
|---|---|
| `agent/core/AgentExecutor` | 自研 ReAct 主循环（上限 20 步），LLM 推理 → 工具调用 → 结果注入 → 下一轮 |
| `agent/core/AgentContext` / `AgentResult` / `AgentStepEvent` | 执行上下文、结果、SSE 步骤事件 |
| `agent/core/AgentPrompts` | 系统提示词模板（全量 / 渐进披露两套） |
| `agent/core/ToolExecutionTimeout` | 工具超时控制（普通 180s，工作流 600s） |
| `agent/adapter/SseChatAdapter` | SSE 流式响应适配器（超时 30 分钟，覆盖澄清等待） |

#### 工具与技能注册中心

- `agent/tool/ToolRegistry` — Spring 自动发现所有 `Tool` Bean 并注册，生成 LangChain4j `ToolSpecification`
- `agent/skill/SkillRegistry` — 扫描 `skills/` 目录下的 `SKILL.md`（YAML frontmatter），三阶段按需加载使用
- **渐进披露模式**：当工具 + 技能总数超过 `agent.disclosure.threshold`（默认 5）时，LLM 仅看到 `resolve` 元工具；调用 `resolve` 后对应工具规格动态注入下一轮
- 当前注册的工具位于Linxing_Agent\src\main\java\org\linxing\linxing_agent\agent\tool
- 当前注册的技能位于Linxing_Agent\src\main\java\org\linxing\linxing_agent\agent\skill

#### 多 Agent 工作流（`agent/subagent/`，基于 `langchain4j-agentic`）

`study_plan` 工作流采用两阶段顺序编排（`sequenceBuilder`）：

1. **知识收集**：`KnowledgeCollectionWorkflowService` + `KnowledgeCollectorAgent` —— 可选 HumanInTheLoop 澄清 + 自主调用搜索工具收集素材，写入 `AgenticScope.materials`
2. **内容生成**：`ContentGenerationWorkflowService` + `PlanGeneratorAgent`（+ 条件 `ExamGeneratorAgent`）—— 基于素材生成计划 JSON / 测验 JSON，校验后持久化

关键类：`StudyPlanWorkflowAgent`（`@Agent` 接口）、`SubAgentContext`（线程上下文，对 LLM 不可见）、`PendingClarificationRegistry`（HumanInTheLoop 唤醒）、`JsonSanitizer`、`KnowledgeSearchTools`。

#### Agent 记忆（`agent/memory/`）

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

### LLM 配置
通过 `rag.llm.default-provider` 选择（`minimax` / `deepseek` / `glm` / `kimi`等），均走 OpenAI 兼容 API。DeepSeek 支持 thinking tokens（`return-thinking: true`、`send-thinking`）。`LlmManager` 统一管理 `CHAT_MODEL` 类型。`common/constant/LlmType` 定义各用途的模型 provider 常量

### Python 文档解析服务
`document_analysis_service` 是 Node-Based RAG 的唯一解析入口，通过 `rag.python-service.url`调用。需先于后端启动：
- pdf/docx 单例懒加载并注入图片目录（`IMAGE_STORE_DIR`），避免未用时强制加载 fitz/pdfplumber/python-docx
- 图片直接保存到 Java 的 `storePath/chunk_images/{userId}/{docId}/`，Java 无需搬运
- 详见 [document_analysis_service/README.md](document_analysis_service/README.md)

### Redis 语义缓存
Redis 同时承担会话消息缓存（`RAG_CACHE_SESSION_MSGS_TTL`）与基于 Vector Set 的语义缓存（`RAG_SEMANTIC_CACHE_ENABLED`、`threshold`、`quantization`）。

### ONNX runtime
重排序器使用 `langchain4j-onnx-scoring` + `ms-marco-MiniLM-L-6-v2`。ONNX 原生库由 Java 库自动下载，无需手动安装。

### Maven 显式声明源码目录
`pom.xml` 显式设置 `<sourceDirectory>src/main/java</sourceDirectory>` 与 `<testSourceDirectory>src/test/java</testSourceDirectory>`。这是默认值但被显式声明，勿改动。

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
