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

### Langfuse 可观测性（agent 运行观测）

一次用户 chat 请求 = 1 条 Trace（root span `agent-run`，`ChatServiceImpl` 入口建 / 出口闭），span 按 Langfuse v4 OTLP 语义约定写入。设计稿见 `reference/TODOS/langfuse/0816LangfuseObservability.md`（Phase1）与 `0816LangfuseObservabilityPhase2.md`（Phase2，P0 三项）。

**方案路线**：自定义 `LangfuseChatModelListener` + OTel SDK 直连 Langfuse OTLP 端点（`OtelTraceConfig`）。**不采用 langchain4j 原生 Observation**——1.13.0 原生只能自动给 model/token/duration/error，input/output/cost 全需自写 handler；且本项目手写 ReAct 循环（直接 `chat(request, handler)`），与官方 AiServices/StreamingChatBuilder 样例调用形态完全不同。全部属性名收敛于 `observability/LangfuseAttributeKeys`。

**组件**（包 `org.linxing.linxing_agent.observability`）：

| 组件 | 职责 |
|---|---|
| `OtelTraceConfig` | 构建 `Tracer` Bean。`langfuse.enabled=false` 返回 no-op Tracer，不建 exporter、零开销；OTLP exporter 自动补全 `/v1/traces` 路径、Basic auth（pk:sk）、`x-langfuse-ingestion-version: 4` 头；`LoggingSpanExporter` 把每批导出成败显式打到日志（冒烟期定位「span 建了但 Langfuse 没收到」） |
| `LangfuseProperties` | `langfuse.*` 配置绑定（enabled/endpoint/public-key/secret-key/environment/version/trace-offline-calls） |
| `AgentObservability` | 观测门面：root/tool/retrieval/sub-agent span 建闭 + trace 级属性每 span 冗余注入（`applyTraceAttrs`） |
| `ObservableContext` | ThreadLocal 栈传播观测上下文（span 引用 + trace 属性 + 主循环 step 号），`makeCurrent` 跨线程恢复 |
| `LangfuseChatModelListener`(+`LangfuseChatModelListenerFactory`) | generation span（每轮 LLM 调用）；attributes map 贯穿三回调跨线程携带 span 引用 |
| `MessageSerializer` | ChatMessage/ChatResponse → OpenAI-compatible JSON；图片摘要化不落 base64；统一截断 |
| `SubAgentStepListener` | 子 Agent span（before/after/error 钩子建闭 `Agent: xxx`，见 `agent/core/`） |

**span 层级**：

```
Trace（一次 chat 请求）
└─ root span: agent-run（SERVER，承载 session/user/request_id/question/answer/tags/version/environment）
   ├─ generation span: chat（CLIENT，每轮 LLM 调用；gen_ai.* + usage_details + metadata.step_number/thinking_tokens/temperature）
   ├─ Tool: {toolName}（主循环每次工具调用；metadata.kind=tool + tool_kind/success/duration_ms）
   │   └─ 工具内 LLM 调用（子 Agent）的 generation 挂 tool span 下
   ├─ Retriever: search_knowledge_base（RAG 检索；metadata: vector_store/similarity/reranker/recall_size/
   │      vector_candidates/bm25_candidates/hybrid/score_threshold/before_filter/after_filter/hit/scores）
   └─ Agent: {agentName}（工作流子 Agent；metadata.kind=agent + role）
```

**接入点**：`ChatServiceImpl`（`beginTraceRoot`/`endTraceRoot`）、`AgentExecutor`（主循环每轮工具调用 `beginTool`/`endTool` + `ObservableContext.setCurrentStep` 写 generation 的 step_number）、`ToolExecutionTimeout`（工具线程 `makeCurrent` 恢复 agent 线程捕获的上下文，使工具内 LLM 调用挂 tool span 下）、`SubAgentStepListener`（子 Agent span）、`SearchServiceImpl`（`beginRetrieval`/`endRetrieval`/`endRetrievalError`，主循环 + 子 Agent 两入口，HTTP 直连无观测上下文时 no-op）、`LlmManager`（流式 + 非流式 builder 均挂 listener，全站 LLM 调用均打 generation span）。

## Critical gotchas

### 前端代理剥离 `/api` 前缀
`vue.config.js` 重写 `^/api` → `''`。前端调用 `/api/agent/chat`，后端收到 `/agent/chat`。另有 `/chunk_images` 直连代理（不带 `/api` 前缀，对应后端 `WebMvcConfig` 暴露的静态图片资源）。新增接口时匹配后端路径（无 `/api` 前缀）。

### Node 体系是唯一入库路径
所有文件类型统一走 `IngestServiceImpl` → `DocumentAnalysisFacade.analyze` → `ChunkIngestCoordinator.processDocumentFromNodes`。旧 `ChunkIngestCoordinator.processDocument`（基于 `ChunkStrategyFactory` 按文件类型分派）已 `@Deprecated`，调用直接抛 `UnsupportedOperationException`。`rag/strategy/`（8 个旧策略）与 `rag/render/`（3 个 Renderer）包整体 `@Deprecated` 但未删除，结构识别逻辑已迁移至 Python 侧 parsers。

### Python 服务需先于后端启动
Node-Based RAG 的文档解析依赖 `document_analysis_service`（默认 `http://localhost:18000`；本机 8000 落在 Hyper-V/WSL 保留端口段不可用）。`DocumentAnalysisFacade` 失败时会 fallback 到 `JavaDocumentAnalysisServiceImpl`，但 Java 备用方案**当前尚未实现**，调用会报错。开发时务必先启动 Python 服务。

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
- **PDF 主路径走 MinerU 云托管解析**（`parsers/mineru_client.py`，官方 v4 Bearer token，配置 `MINERU_API_KEY` 后启用）：上传→轮询→下载结果 zip，读 `content_list.json` 映射 Node（含 page/bbox/formula/表格 HTML/代码，支持扫描件 OCR）；未配置 key、超 MinerU 上限（200MB/200页）、或云端失败时自动回退本地 PyMuPDF + pdfplumber 兜底（`_parse_legacy`）
- pdf/docx 单例懒加载并注入图片目录（`IMAGE_STORE_DIR`），避免未用时强制加载 fitz/pdfplumber/python-docx
- 图片直接保存到 Java 的 `storePath/chunk_images/{userId}/{docId}/`，Java 无需搬运
- 图片预估字数 120 参与含图段落累加 flush 判断（避免一遇图就截断文本聚类）
- MinerU 云端异步轮询耗时大头在等待，Java 侧 `rag.python-service.timeout-seconds` 默认 600s，Python 侧 `MINERU_TIMEOUT_SECONDS`（默认 480s）须小于该值（云端超时后回退本地留余量）
- 详见 [document_analysis_service/README.md](document_analysis_service/README.md)

### Redis 缓存
Redis 承担三类缓存：
- **Runtime Mirror**（`mirror:msgs` / `mirror:steps`，TTL `rag.cache.mirror-ttl` 默认 43200s=12h）—— Recovery 与 StepRecorder 的首选源
- **幂等缓存**（`chat:response:{requestId}`，TTL `rag.cache.chat-response-ttl` 默认 2100s=35min，略大于 SSE 超时 30min）
- 旧会话消息缓存（`session-messages-ttl`，已 @deprecated，P3 Runtime Mirror 落地后停写）、文档预览缓存（`doc-preview-ttl`）、Agent 步骤缓存（`agent-steps-ttl`，已 @deprecated）

### Rerank / Embedding（硅基流动 API）
重排序走硅基流动 `POST /v1/rerank`（`SiliconFlowScoringModel` 实现 `ScoringModel`，配置 `rag.api.reranker.*`，默认 `BAAI/bge-reranker-v2-m3`）。API 返回 `relevance_score` 已归一化 [0,1]，`Reranker.scoreAll`/`pickTopKScored` 保留该分数，`SearchServiceImpl` 直接与 `rag.search.score-threshold` 比较，无需 sigmoid。
向量化走 OpenAI 兼容 `POST /v1/embeddings`（`OpenAiEmbeddingModel` bean，配置 `rag.api.embedding.*`，默认 `BAAI/bge-m3` 1024 维），维度受 `rag.vector-store.dimension` 与 DB 列 `vector(1024)` 约束。旧本地 ONNX（`ms-marco-MiniLM-L-6-v2`，纯英文模型无法处理中文）已停用并删除模型文件。

### Langfuse 观测（enabled 默认 false 零开销，但有几个易错点）
- **`langfuse.enabled` 默认 false**：需在 `application-dev.yaml` 配 `langfuse.endpoint`（Langfuse 控制台 `/api/public/otel`）与 `public-key`/`secret-key`（Basic auth）。
- **endpoint 必须是完整路径 `.../api/public/otel/v1/traces`**：Java SDK 程序化 `setEndpoint()` 按字面使用传入 URL、不会自动追加信号路径（仅环境变量自动装配 `OTEL_EXPORTER_OTLP_ENDPOINT` 会追加），base 形式直 POST 会 404。`OtelTraceConfig.resolveEndpoint` 已统一补全，勿在配置里写重复路径。
- **`observation.type` 只支持 `span / generation / event`**：tool / 子 Agent / retriever 一律写 `type=span`，语义靠 `Tool: xxx` / `Agent: xxx` / `Retriever: xxx` 命名前缀 + `metadata.kind` 表达（官方写端不识别 type=tool/agent）。
- **trace 级属性必须每 span 冗余传播**（`AgentObservability.applyTraceAttrs`）：session/user/tags/version/environment/request_id/question 只在 root 写会让 Langfuse 按 user/session 过滤时缺数据。
- **input/output 截断**：input 4000 chars，output / messages / response 20000 chars；图片不落 base64 原文（摘要化为 `[图片]` 标记 + 来源/长度）。
- **cost 不在代码侧算**：`langfuse.observation.cost_details` 字段预留但未写，成本由 Langfuse 控制台 **model 定价表** 按 usage tokens 计算（本项目为自定义/中转 provider，需在控制台为各模型配一次定价）。
- **`langfuse.trace-offline-calls` 默认 false**：离线 LLM 调用（RAG 语义增强 / 摘要 / Memory Worker 后台）无观测上下文时静默跳过，不入 trace。
- 子 Agent 内部工具调用只产生 step 事件（无 `Tool: xxx` span）；其内部 RAG 检索由 `SearchServiceImpl` 独立打的 `Retriever:` span 覆盖（子 Agent span 下）。

### Maven 显式声明源码目录
`pom.xml` 显式设置 `<sourceDirectory>src/main/java</sourceDirectory>` 与 `<testSourceDirectory>src/test/java</testSourceDirectory>`。这是默认值但被显式声明，勿改动。

## Key dependencies

### 后端

| Library | Purpose |
|---|---|
| `langchain4j` 1.13.0 | 核心 RAG 框架 |
| `langchain4j-agentic` | 多 Agent 工作流（@Agent / conditionalBuilder / humanInTheLoopBuilder） |
| `langchain4j-web-search-engine-tavily` 1.13.0-beta23 | Tavily 联网搜索 |
| `langchain4j-embeddings-bge-small-zh-v15` | 本地嵌入模型（已停用，改调硅基流动 API bge-m3） |
| `langchain4j-pgvector` 0.1.6 | PG 向量存储 |
| `langchain4j-open-ai` | LLM 客户端（多供应商走 OpenAI 兼容 API） |
| `langchain4j-onnx-scoring` + `onnxruntime` 1.20.0 | 已停用的本地 Cross-encoder 重排序（改调硅基流动 API rerank） |
| `mybatis-spring-boot-starter` 4.0.0 | ORM（XML mappers） |
| `druid-spring-boot-4-starter` 1.2.28 | 连接池（专用 Spring Boot 4 starter） |
| `spring-boot-starter-data-redis`（Lettuce） | Redis 缓存 / Runtime Mirror |
| `caffeine` | 技能指令 LRU 缓存 / 激活集 / RuleSetStore |
| `jtokkit` 1.1.0 | OpenAI 兼容 BPE tokenizer（替代 length()/2 启发式） |
| `jjwt` 0.12.6 | JWT 认证 |
| `jsoup` 1.18.3 | HTML 解析（旧 HtmlChunkStrategy，Node 体系下未使用） |
| `jieba-analysis` 1.0.2 | 中文分词（BM25） |
| `pdfbox` 3.0.1 | PDF 解析（旧路径，Node 体系下 PDF 由 Python 服务解析） |
| `opentelemetry-api` / `opentelemetry-sdk` / `opentelemetry-sdk-trace` / `opentelemetry-exporter-otlp` 1.55.0 | Langfuse 观测：OTel SDK 直连 OTLP 端点导出 span |
| `spring-security-crypto` | 密码加密 |

### Python 文档解析服务（`document_analysis_service/`）

| Library | Purpose |
|---|---|
| `fastapi` 0.115.6 + `uvicorn[standard]` 0.34.0 | Web 框架 |
| `python-multipart` 0.0.20 | multipart/form-data 文件上传 |
| `requests` >=2.31.0 | MinerU 云托管 API 客户端（申请上传 URL / PUT 上传 / 轮询 / 下载 zip） |
| `PyMuPDF` (fitz) >=1.24.0 | PDF 本地兜底解析：文本/图片抽取、字号扫描 |
| `pdfplumber` >=0.11.0 | PDF 本地兜底解析：表格抽取 |
| `python-docx` >=1.1.0 | DOCX 解析 |
| `mistune` >=3.3.2 | Markdown 结构识别 |
| `beautifulsoup4` >=4.15.0 | HTML DOM 遍历 |
| `Pillow` >=10.0.0 | 图片处理 |
