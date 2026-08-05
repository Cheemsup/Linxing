# AGENTS.md — Linxing (Agent-Driven Learning Platform)

## Project overview

Monorepo: Spring Boot 4.x 后端（`Linxing_Agent/`）+ Vue 3 前端（`webconsole/`）+ Python 文档解析服务（`document_analysis_service/`）。
Agent 驱动的个人学习平台 —— 自研 ReAct Agent 主循环 + `langchain4j-agentic` 多 Agent 工作流，在 PG 向量知识库（Node-Based RAG）、联网搜索之上提供对话、学习计划生成、知识测验出题与联网搜索能力。

## Architecture

- **后端 package**：`org.linxing.linxing_agent`
- **DDD 分层**：`common/`（共享基础设施）→ `user/`（认证）→ `rag/`（知识检索）→ `agent/`（对话编排、工具、技能、记忆、子 Agent 工作流）
- **认证**：JWT via `JwtTokenUserInterceptor`，`addPathPatterns("/**")`，仅排除 `/user/login` 与 `/user/register`（后端路径无 `/api` 前缀）

### Node-Based RAG 架构（rag 域核心）

文档给到 Python 侧划分出原子化 Node 数据（详见 [document_analysis_service/README.md](document_analysis_service/README.md)），Node List 回到 Java 侧完成图片解读、语义增强、拼装等（保障 Node 原子性的情况下消费 Node），以及后续向量化 chunk 等。

**活跃入库链路（唯一路径）**：
`IngestServiceImpl` → `DocumentAnalysisFacade.analyze`（Python 优先，Java 备用方案未实现（20260804））→ `SemanticEnhancementService`（IMAGE/CODE/TABLE 调 LLM 增强）→ `NodeBasedChunkBuilder.build` → `ChunkIngestCoordinator.processDocumentFromNodes`（两 pass 插入：先父块后子块）→ 责任链后处理（标题提取、tsContent、向量化）→ `embeddingPersist.flush`

#### Display/Index 文本双轨（内联实现）

- **chunkText（Display）**：`originalContent()` 拼接，保留原文形态（图片/代码/表格为占位符 `[[LINXING:TYPE:nodeId]]`），前端通过 `nodeMetadata`（JSONB）还原
- **indexText（Index）**：`semanticText()` 拼接，含 VLM/LLM 语义增强结果，供 Embedding + BM25 使用
- 下游 `EmbeddingPersist`/`FullTextIndexer` 优先读 `indexText`，缺失回退 `chunkText`
- 双轨逻辑已**直接内联进 `NodeBasedChunkBuilder.buildChunkFromNodes`**，独立 Renderer 类（`render/` 包）已 `@Deprecated` 不再被调用

#### 父子装配（Small-to-Big）

- 基于 `groupId` 聚相邻 Node：有 groupId 的相邻 Node 同属一组 → 1 个 **Level1 父块**（isSearchable=false，不参与检索）+ N 个 **Level2 子块**（parentChunkId 指向父块，可检索）
- 隔离性：有组与无组、不同组之间不拼接

#### 混合检索（向量 + BM25 + RRF + ONNX 重排序）

`SearchServiceImpl` 流程：
1. 向量检索（recallSize 召回）
2. 若 hybrid：`KeywordExtractor` 提关键词 → `chunkMapper.bm25Search` → `ReciprocalRankFusion`（向量权重 0.7，BM25 权重 0.3，K=60）
3. **对全部候选统一 Cross-Encoder 打分**（`reranker.scoreAll`，与 topK 无关）
4. **父块去重展开（small2big）**：`expandScoredToParent` 在 limit(topK) 前做，同父块取最高分小块代表替换为父块文本（保留小块分数）
5. `reranker.pickTopKScored` 截断 topK
6. **sigmoid 归一化 + 阈值过滤**：`1/(1+e^-x)` 归一到 [0,1]，低于 `rag.search.score-threshold`（默认 0.35）舍弃，**即使导致结果为空也舍弃**
- `RagSearchTool` 空结果降级：阈值过滤后为空时返回提示文本而非空 JSON 数组，避免 LLM 误判工具故障反复重试

### Agent 域核心组件

| 组件 | 职责 |
|---|---|
| `agent/core/AgentExecutor` | 自研 ReAct 主循环，LLM 推理 → 工具调用 → 结果注入 → 下一轮 |
| `agent/core/AgentContext` | 单次会话运行时上下文：含 `StepRecorder stepRecorder`（统一步骤记录器，主循环与工作流共享） |
| `agent/core/AgentStepEvent` | SSE 步骤事件 DTO：含 `parentStepId`/`agentId`/`stepId` 层级字段（0724 改造，支持层次 step） |
| `agent/core/AgentStepTypes` | 步骤类型/phase/stepData key 常量词汇表：含 `TOOL_PROGRESS`/`SKILL_ACTIVATED`/`SUB_AGENT`、tool_kind 取值（function/skill/mcp/workflow） |
| `agent/core/StepRecorder` | 统一步骤记录器：SSE 推送 + agent_steps 持久化 + VO 累积；parent 栈 + agent 上下文栈（ThreadLocal）维护层级归属；持久化后即时镜像到 RuntimeMirror |
| `agent/core/ToolExecutionTimeout` | 工具超时 watchdog + 心跳推送（普通 180s，工作流 600s）；心跳间隔 1s，elapsedSeconds 用真实墙钟时间；支持 pause/resume（HumanInTheLoop 期间暂停计时） |
| `agent/core/HumanInTheLoopFactory` | 阻塞式澄清 Agent 公共工厂，可复用 |
| `agent/core/PendingClarificationRegistry` | HumanInTheLoop pending 状态管理：注册/完成/取消/超时自清理 |
| `agent/adapter/SseChatAdapter` | SSE 流式响应适配器（超时 30 分钟，覆盖澄清等待）；含 requestId 幂等缓存（reset 后 retry 复用） |

#### 工具与技能注册中心

- `agent/tool/ToolRegistry` — `ApplicationListener<ContextRefreshedEvent>`，自动发现所有 `Tool` Bean 并注册（跳过 @Deprecated 与 `shouldRegisterToMainAgent()=false`），生成 LangChain4j `ToolSpecification`
- `agent/skill/SkillRegistry` — 扫描 `skills/` 目录下 `SKILL.md`（YAML frontmatter），三阶段按需加载（Phase1 元数据 / Phase2 正文 Caffeine LRU / Phase3 资源文件）
- **渐进披露**：工具 + 技能总数超过 `agent.disclosure.threshold`（默认 5）时，LLM 仅看到 `resolve` 元工具；调用 `resolve` 后对应工具规格动态注入下一轮。激活集 per-session 隔离，跨同 session 多次 chat 复用，Caffeine TTL 兜底回收

当前注册工具（`agent/tool/impl/`）：

| 工具 | name | 职责 |
|---|---|---|
| `CatalogTool` | `catalog` | **@Deprecated**（目录已注入 SystemPrompt，保留兜底） |
| `ResolveTool` | `resolve` | 统一解析工具 |
| `WebSearchTool` | `web_search` | 联网搜索 |
| `RagSearchTool` | `search_knowledge_base` | 检索个人知识库 |
| `StartStudyPlanWorkflowTool` | `start_study_plan_workflow` | 启动 study_plan 工作流（600s 超时） |
| `SaveStudyPlanTool` / `SaveExamTool` | `save_study_plan` / `save_exam` | 保存计划/测验 |
| `CreateContainerTool` / `AppendToContainerTool` / `ReplaceInContainerTool` / `ReplaceContainerMetadataTool` / `RemoveFromContainerTool` | `create_container` 等 | JSON 容器分批构建工具 |
| `ReadMemoryTool` / `WriteMemoryTool` / `ListMemoryTool` | `read_memory` / `write_memory` / `list_memory` | 长期记忆工具（WriteMemoryTool `shouldRegisterToMainAgent=false`，仅 Memory Worker 内部调用） |

当前技能（`agent/skill/skills/`）：`study_plan`（学习计划）、`exam`（知识测验），共享资源 `skills/_shared/references/batch-json-pattern.md`（分批构建 JSON 模式）。

#### 多 Agent 工作流（`agent/subagent/`，基于 `langchain4j-agentic`）

`study_plan` 工作流采用**两阶段顺序编排**：

1. **知识收集**（`KnowledgeCollectionStage`）：条件触发 `clarifyConditional`（HumanInTheLoop 阻塞等待，超时 1500s=25min）+ `KnowledgeCollectionAgent`（自主调 webSearchTool/ragSearchTool 收集素材，outputKey=materials）
2. **内容生成**（`ContentGenerationStage`）：`PlanGenerationAgent`（用 JSON 容器工具分批构建计划）+ 条件 `ExamGenerationAgent`（测验生成）+ `persistResults`（统一回填 exam.linked_plan_id）

关键类：`StudyPlanner`（顶层编排）、`StudyPlanAgent`（`@Agent` 接口）、`SubAgentContext`（ThreadLocal 业务上下文，对 LLM 不可见）、`JsonSanitizer`（LLM 输出 JSON 清洗）、`KnowledgeSearchToolSet`。

#### Agent 记忆（`agent/memory/`）

记忆体系已重构为**短期记忆 + Projection + Redis Mirror + 长期记忆**四层，旧 `WindowMemory`/`SummaryMemory` 已 `@Deprecated`（移入 `deprecated/`）。

**短期记忆 + Projection（`agent/memory/window/`）**：

- `runtime/RuntimeAgentMemory` — 极简累加器，memory 职责退化为纯列表累加，不再负责窗口/驱逐/Projection；SystemMessage 由 ContextBuilder 一次性装配
- `builder/DefaultContextBuilder` — **核心装配类**，三段职责：A 系统段、B 历史段（投影）、C 工具规格段；负责 token 估算 + Projection 策略判定 + 同步/异步 Projection 触发；两次 assemble 破循环依赖
- `recovery/HistoryRecoveryService` — **Recovery 机制核心**：从锚点消息沿 parentId 回溯重建含 tool 调用/结果的历史；Redis-mirror-first（两 Hash 皆命中则内存回溯），miss/异常退化到 DB + cache-aside 热身 Mirror；tool_result 缺失补占位符避免 OpenAI 协议硬错
- `SummaryService` — Summary 独立持久化（type=SUMMARY 挂在路径末端），落库后镜像到 mirror:msgs
- `projection/ProjectionPolicy` — 四级策略枚举：FULL / REWRITE_TOOL / SNIP_LOWVALUE / SUMMARY
- `projection/ProjectionLoopExecutor` — Snip/Rewrite 异步小循环编排，两阶段：阶段1 Rewrite（纯规则无 LLM）、阶段2 Snip（LLM ReAct，仅 SNIP_LOWVALUE 触发），per-session CAS 去重
- `projection/rewrite/` — RewriteLoopExecutor + RewriteRuleAnalyzer + RewriteRuleWhitelist（白名单）
- `projection/snip/` — SkipTurnReActLoop（LLM ReAct 小循环）+ rules/ReadCurrentRulesTool + rules/UpdateSkipTurnRuleTool
- `ruleset/` — RuleSetStore（Caffeine 按 sessionId 维护 SkipTurnRule+RewriteToolRule，TTL=mirrorTtl）

**Projection 三段式**：
- **Rewrite**（纯规则）：按白名单+阈值把读性质工具超长结果产为 RewriteToolRule（丢 content 留简要字段）
- **Snip**（LLM ReAct）：按 Turn 判定低价值（寒暄/进度确认/重复试错/已失效中间探索）产 SkipTurnRule
- **Summary**（同步执行）：压缩为 Summary 节点落库（由 ChatServiceImpl 落盘，Builder 全程不调 SummaryService）

**Redis Mirror（Runtime Mirror，`agent/memory/service/impl/`）**：
- `RuntimeMirrorServiceImpl` — session 粒度双 Hash：`mirror:msgs:{sessionId}`（field=msgId）+ `mirror:steps:{sessionId}`（field=stepId）；HPUT 幂等覆盖，每次写 expire 续期
- 降级契约：所有方法 try-catch + 降级日志，绝不向上抛，**正确性不依赖 Redis**
- `StepRecorder.persist` 后即时 `mirrorStep`

**长期记忆（`agent/memory/longterm/`）**：
- `workspace/MemoryWorkspace` — 受限沙盒，按 userId 隔离，物理位置 `files_store/memory/{userId}/`；沙盒越界校验（`..` 与绝对路径拒绝）
- `workspace/MemoryTemplates` — V1 最小模板（Agent.md/User.md/Directory.md/Learning/Current.md + History 归档）
- `injector/LongMemoryInjector` — 长期记忆常驻段装配：Directory 全文 + Agent/User/Current 头部摘要 + History 元信息
- `worker/MemoryWorkerReActLoop` — Memory Worker ReAct 小循环，回答完成后异步触发，判断是否需长期化并调 read_memory/write_memory（只更新当前最新状态，不新增/删除 Section）
- `tool/` — ReadMemoryTool / WriteMemoryTool / ListMemoryTool（Agent 可调用的记忆工具）
- `MemoryController` — 用户入口（`/agent/memory/*`），用户写直接落盘，绕过异步 Memory Worker

## Critical gotchas

### 前端代理剥离 `/api` 前缀
`vue.config.js` 重写 `^/api` → `''`。前端调用 `/api/agent/chat`，后端收到 `/agent/chat`。另有 `/chunk_images` 直连代理（不带 `/api` 前缀，对应后端 `WebMvcConfig` 暴露的静态图片资源）。新增接口时匹配后端路径（无 `/api` 前缀）。

### Node 体系是唯一入库路径
所有文件类型统一走 `IngestServiceImpl` → `DocumentAnalysisFacade.analyze` → `ChunkIngestCoordinator.processDocumentFromNodes`。旧 `ChunkIngestCoordinator.processDocument`（基于 `ChunkStrategyFactory` 按文件类型分派）已 `@Deprecated`，调用直接抛 `UnsupportedOperationException`。`rag/strategy/`（8 个旧策略）与 `rag/render/`（3 个 Renderer）包整体 `@Deprecated` 但未删除，结构识别逻辑已迁移至 Python 侧 parsers。

### Python 服务需先于后端启动
Node-Based RAG 的文档解析依赖 `document_analysis_service`（默认 `http://localhost:8000`）。`DocumentAnalysisFacade` 失败时会 fallback 到 `JavaDocumentAnalysisServiceImpl`，但 Java 备用方案**当前尚未实现**，调用会报错。开发时务必先启动 Python 服务。

### 语义增强结果必须进入 indexText
Node-Based 架构的核心价值在于 VLM/LLM 语义增强提升检索质量。`NodeBasedChunkBuilder.buildChunkFromNodes` 同时生成 `chunkText`（Display）与 `indexText`（Index，含语义增强结果）。下游 `EmbeddingPersist`/`FullTextIndexer` 优先读 `indexText`，缺失才回退 `chunkText`。新增检索相关 Handler 时必须沿用此优先级，否则语义增强会变成空转。

### 后端路径与拦截器
JWT 拦截器 `addPathPatterns("/**")`，仅排除 `/user/login` 与 `/user/register`。所有 `/agent/**`、`/exam/**`、`/study-plan/**`、`/rag/**`、`/agent/memory/**` 接口均需携带 Bearer Token。

### `application-dev.yaml` 被忽略但必需
包含 DB 密码、各 LLM API key、`TAVILY_API_KEY`、`JWT_SECRET_KEY`、模型路径。本地必须存在。非密钥配置项可见于 `application.yaml`。

### LLM 配置
`llm.*`（顶级配置，非嵌套在 `rag:` 下）通过 `rag.llm.default-provider` 选择（`minimax` / `deepseek` / `glm` / `kimi` / `other1` 等），均走 OpenAI 兼容 API。DeepSeek 支持 thinking tokens（`return-thinking: true`、`send-thinking`）。`LlmManager` 统一管理 `CHAT_MODEL` 类型。`common/constant/LlmType` 定义各用途 provider 常量（含新增 `MEMORY_WORKER_MODEL`）。

### Python 文档解析服务
`document_analysis_service` 是 Node-Based RAG 的唯一解析入口，通过 `rag.python-service.url` 调用。需先于后端启动：
- pdf/docx 单例懒加载并注入图片目录（`IMAGE_STORE_DIR`），避免未用时强制加载 fitz/pdfplumber/python-docx
- 图片直接保存到 Java 的 `storePath/chunk_images/{userId}/{docId}/`，Java 无需搬运
- 图片预估字数 120 参与含图段落累加 flush 判断（避免一遇图就截断文本聚类）
- 详见 [document_analysis_service/README.md](document_analysis_service/README.md)

### Redis 缓存
Redis 承担三类缓存：
- **Runtime Mirror**（`mirror:msgs` / `mirror:steps`，TTL `rag.cache.mirror-ttl` 默认 43200s=12h）—— Recovery 与 StepRecorder 的首选源
- **幂等缓存**（`chat:response:{requestId}`，TTL `rag.cache.chat-response-ttl` 默认 2100s=35min，略大于 SSE 超时 30min）
- 旧会话消息缓存（`session-messages-ttl`，已 @deprecated，P3 Runtime Mirror 落地后停写）、文档预览缓存（`doc-preview-ttl`）、Agent 步骤缓存（`agent-steps-ttl`，已 @deprecated）

### ONNX runtime
重排序器使用 `langchain4j-onnx-scoring` + `ms-marco-MiniLM-L-6-v2`。ONNX 原生库由 Java 库自动下载，无需手动安装。`Reranker.scoreAll`/`pickTopKScored` 保留原始 logits 供 sigmoid 归一化，区别于旧 `rerank`/`pickTopK`（丢弃分数）。

### Maven 显式声明源码目录
`pom.xml` 显式设置 `<sourceDirectory>src/main/java</sourceDirectory>` 与 `<testSourceDirectory>src/test/java</testSourceDirectory>`。这是默认值但被显式声明，勿改动。

## Key dependencies

### 后端

| Library | Purpose |
|---|---|
| `langchain4j` 1.13.0 | 核心 RAG 框架 |
| `langchain4j-agentic` | 多 Agent 工作流（@Agent / conditionalBuilder / humanInTheLoopBuilder） |
| `langchain4j-web-search-engine-tavily` 1.13.0-beta23 | Tavily 联网搜索 |
| `langchain4j-embeddings-bge-small-zh-v15` | 本地嵌入模型（512 维） |
| `langchain4j-pgvector` 0.1.6 | PG 向量存储 |
| `langchain4j-open-ai` | LLM 客户端（多供应商走 OpenAI 兼容 API） |
| `langchain4j-onnx-scoring` + `onnxruntime` 1.20.0 | Cross-encoder 重排序 |
| `mybatis-spring-boot-starter` 4.0.0 | ORM（XML mappers） |
| `druid-spring-boot-4-starter` 1.2.28 | 连接池（专用 Spring Boot 4 starter） |
| `spring-boot-starter-data-redis`（Lettuce） | Redis 缓存 / Runtime Mirror |
| `caffeine` | 技能指令 LRU 缓存 / 激活集 / RuleSetStore |
| `jtokkit` 1.1.0 | OpenAI 兼容 BPE tokenizer（替代 length()/2 启发式） |
| `jjwt` 0.12.6 | JWT 认证 |
| `jsoup` 1.18.3 | HTML 解析（旧 HtmlChunkStrategy，Node 体系下未使用） |
| `jieba-analysis` 1.0.2 | 中文分词（BM25） |
| `pdfbox` 3.0.1 | PDF 解析（旧路径，Node 体系下 PDF 由 Python 服务解析） |
| `spring-security-crypto` | 密码加密 |

### Python 文档解析服务（`document_analysis_service/`）

| Library | Purpose |
|---|---|
| `fastapi` 0.115.6 + `uvicorn[standard]` 0.34.0 | Web 框架 |
| `python-multipart` 0.0.20 | multipart/form-data 文件上传 |
| `PyMuPDF` (fitz) >=1.24.0 | PDF 文本/图片抽取 |
| `pdfplumber` >=0.11.0 | PDF 表格抽取 |
| `python-docx` >=1.1.0 | DOCX 解析 |
| `mistune` >=3.3.2 | Markdown 结构识别 |
| `beautifulsoup4` >=4.15.0 | HTML DOM 遍历 |
| `Pillow` >=10.0.0 | 图片处理 |
