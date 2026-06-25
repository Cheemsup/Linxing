# Linxing — Agent 驱动的个人学习平台

基于 **LangChain4j 1.13 + BGE-small-zh-v1.5 + PostgreSQL/pgvector**，自研 ReAct Agent + `langchain4j-agentic` 多 Agent 工作流，在个人笔记知识库之上提供对话问答、学习计划制定、知识测验出题与联网搜索能力。

## 项目简介

Linxing 起初是一个个人笔记 RAG 问答系统，现已演进为 Agent 驱动的学习平台：

- **RAG 作为能力而非入口**：知识库检索被封装为 `search_knowledge_base` 工具，由 Agent 自主决定何时调用
- **自研 ReAct Agent**：LLM 推理 → 工具调用 → 结果注入循环，支持渐进披露、JSON 容器结构化输出、工具超时控制
- **多 Agent 工作流**：基于 `langchain4j-agentic` 的两阶段顺序编排（知识收集 → 内容生成），用于学习计划与测验
- **HumanInTheLoop**：工作流可在关键节点暂停等待用户澄清回复后继续执行
- **联网搜索**：集成 Tavily，弥补个人笔记知识盲区
- **混合检索**：向量检索 + BM25 全文检索 + RRF 融合 + Cross-encoder 重排序
- **语义缓存**：基于 Redis Vector Set 的回答级语义缓存
- **树形对话**：多轮对话以树形结构组织，可追溯任意分支
- **多用户隔离**：JWT 认证 + 用户级数据隔离

### 核心特性

| 能力 | 说明 |
|------|------|
| ReAct Agent | 自研主循环，最多 20 步；工具数较多时启用渐进披露（先看目录，再 resolve 取规格） |
| 多 Agent 工作流 | `study_plan` 工作流：知识收集 Agent + 计划生成 Agent + 条件出题 Agent，顺序编排 |
| 多格式文档导入 | TXT、Markdown、PDF、Word、Excel、HTML、CSV、Java 代码等 |
| 智能分块 | 策略模式按文档类型自动选择（Markdown / HTML / 代码 / 语义 / 递归兜底） |
| 混合检索 | 向量检索 + BM25 + RRF 融合 + ONNX Cross-encoder 重排序 |
| 学习计划 | 分阶段结构化计划，支持阶段进度更新与 Markdown / HTML 导出 |
| 知识测验 | 单选 / 多选 / 填空 / 判断 / 简答，作答后自动批改 |
| 多 LLM | MiniMax、DeepSeek（支持 thinking）、GLM、Kimi（均走 OpenAI 兼容 API） |

## 技术栈

### 后端

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.0.5 | 核心框架 |
| LangChain4j | 1.13.0 | RAG + Agent 框架 |
| langchain4j-agentic | 1.13.0 | 多 Agent 工作流（@Agent / sequenceBuilder） |
| langchain4j-tavily | 1.13.0-beta23 | 联网搜索 |
| PostgreSQL + pgvector | - | 向量数据库 |
| MyBatis | 4.0.0 | ORM 框架 |
| Druid | 1.2.28 | 数据库连接池 |
| ONNX Runtime | 1.20.0 | 重排序模型推理 |
| BGE-small-zh-v1.5 | - | 中文嵌入模型（512 维） |
| Redis + Jedis | 6.2.0 | 语义缓存 / 会话消息缓存 |
| Caffeine | - | 技能指令 LRU 缓存 |
| JJWT | 0.12.6 | JWT 认证 |
| Jsoup / Jieba / PDFBox | - | HTML / 中文分词 / PDF 解析 |

### 前端

| 技术 | 版本 | 说明 |
|------|------|------|
| Vue | 3.2.13 | 前端框架 |
| Element Plus | 2.13.7 | UI 组件库 |
| Vue Router | 4 | 路由管理 |
| Axios | 1.15.2 | HTTP 客户端 |
| vue3-d3-tree | 1.0.2 | 对话树可视化 |

## 项目结构

```
Linxing/
├── Linxing_Agent/                         # 后端项目
│   ├── src/main/java/org/linxing/linxing_agent/
│   │   ├── common/                        # 共享基础设施
│   │   │   ├── config/                    # LlmManager / RedisConfig / WebMvcConfig ...
│   │   │   ├── interceptor/               # JwtTokenUserInterceptor
│   │   │   ├── security/                  # JwtUtil / PasswordEncoder
│   │   │   └── result/ userInfoMaintainer/
│   │   ├── user/                          # 用户域（登录注册）
│   │   ├── rag/                            # 知识检索域（被封装为 Agent 工具）
│   │   │   ├── controller/                # DocumentController / IngestController / SearchController / ChunkController
│   │   │   ├── service/ strategy/ pipeline/
│   │   │   ├── utils/                      # Reranker / RRF / QueryRewriter / EmbeddingHelper
│   │   │   └── entity/ dto/ vo/ mapper/
│   │   └── agent/                          # Agent 域（业务编排核心）
│   │       ├── core/                       # AgentExecutor（ReAct 主循环）/ AgentContext / AgentPrompts
│   │       ├── adapter/                    # SseChatAdapter（SSE 流式响应）
│   │       ├── controller/                 # ChatController / ExamController / StudyPlanController
│   │       ├── tool/                       # ToolRegistry + 12 个 Tool 实现
│   │       ├── skill/                      # SkillRegistry / SkillLoader（扫描 SKILL.md）
│   │       ├── catalog/                    # CatalogProvider 渐进披露目录
│   │       ├── memory/                     # AgentMemory / WindowMemory / SummaryMemory
│   │       ├── subagent/                   # 多 Agent 工作流（study_plan 两阶段编排）
│   │       └── entity/ dto/ vo/ mapper/ exception/
│   └── src/main/resources/
│       ├── mapper/{agent,rag}/             # MyBatis XML
│       ├── skills/                         # SKILL.md 技能定义（study_plan / exam）
│       ├── models/                         # ONNX 重排序模型（gitignored）
│       ├── application.yaml                # 主配置（非密钥项）
│       └── schema.sql                      # 数据库建表脚本
│
└── webconsole/                             # 前端项目
    └── src/
        ├── api/{auth,rag/}                 # API 接口
        ├── components/rag/                 # ChatPanel / ChatTreePanel / QuizPanel / StudyPlanTimeline ...
        ├── views/{auth,rag}/               # ChatView / IngestView / NotesView / QuizView / SearchView / StudyPlanView
        ├── layouts/ router/ stores/        # 布局 / 路由 / Pinia
        └── composables/                    # useMarkdownRenderer
```

## 快速开始

### 环境要求

- JDK 17+
- Node.js 16+
- PostgreSQL 14+（需安装 pgvector 扩展）
- Redis 6+（用于语义缓存与会话消息缓存）
- Yarn（前端包管理）

### 1. 数据库准备

```sql
CREATE DATABASE vectordb;
\c vectordb
CREATE EXTENSION vector;
```

执行建表脚本：

```bash
psql -d vectordb -f Linxing_Agent/src/main/resources/schema.sql
```

### 2. 后端配置

在 `Linxing_Agent/src/main/resources/` 下创建 `application-dev.yaml`（已被 `.gitignore` 忽略，需本地配置），至少包含以下密钥与路径：

```yaml
PG_HOST: localhost
PG_PORT: 5432
PG_DATABASE: vectordb
PG_USER: <your_user>
PG_PASSWORD: <your_password>

RAG_STORE_PATH: <本地文档存储绝对路径>
RAG_VECTOR_HOST: localhost
RAG_VECTOR_PORT: 5432
RAG_VECTOR_DATABASE: vectordb
RAG_VECTOR_USER: <your_user>
RAG_VECTOR_PASSWORD: <your_password>

LLM_DEFAULT_PROVIDER: minimax          # minimax / deepseek / glm / kimi
MINIMAX_API_KEY: <your_key>
MINIMAX_BASE_URL: <your_base_url>
MINIMAX_MODEL: <your_model>

TAVILY_API_KEY: <your_key>             # 联网搜索

JWT_SECRET_KEY: <your_jwt_secret>
JWT_TTL: 86400000
JWT_TOKEN_NAME: Authorization
```

> 模型文件 `models/ms-marco-MiniLM-L-6-v2/model.onnx` 与 tokenizer 需放置在 classpath（默认已声明在 `application.yaml`）。上传文档目录 `files_store/` 会被自动创建。

### 3. 后端启动

```bash
cd Linxing_Agent
mvn spring-boot:run
```

后端服务运行在 `http://localhost:8080`

### 4. 前端启动

```bash
cd webconsole
yarn install
yarn serve
```

前端服务运行在 `http://localhost:3000`，开发代理将 `/api/*` 转发到后端并剥离 `/api` 前缀。

## 核心架构

### Agent 执行流程

```
用户提问 → SSE 流式响应
              ↓
    AgentExecutor（ReAct 主循环，上限 20 步）
              ↓
    构建系统提示词（注入工具 + 技能目录）
              ↓
    ┌── LLM 推理（流式）─────────────────┐
    │                                      │
    │  ① 渐进披露模式：先调用 resolve       │
    │     获取工具/技能完整定义              │
    │                                      │
    │  ② 调用工具：                          │

    │     - search_knowledge_base（RAG）   │
    │     - web_search（Tavily 联网）       │
    │     - start_study_plan_workflow      │
    │       （触发多 Agent 工作流）         │
    │     - create/append/replace_container │
    │       （JSON 结构化输出）             │
    │                                      │
    │  ③ 结果注入记忆 → 下一轮              │
    └──────────────────────────────────────┘
              ↓
    LLM 不再发起工具调用 → 输出最终回答
```

### 多 Agent 工作流（study_plan）

```
start_study_plan_workflow 工具触发
              ↓
   StudyPlanWorkflowService（两阶段顺序编排）
              ↓
   ┌──────────────── 阶段一 ────────────────┐
   │ KnowledgeCollectionWorkflowService     │
   │   ├ 可选澄清（HumanInTheLoop 暂停）     │
   │   └ KnowledgeCollectorAgent            │
   │       自主调用 search_knowledge_base   │
   │       + web_search 收集素材            │
   │       写入 AgenticScope.materials     │
   └─────────────────────────────────────────┘
              ↓
   ┌──────────────── 阶段二 ────────────────┐
   │ ContentGenerationWorkflowService       │
   │   ├ PlanGeneratorAgent → 计划 JSON     │
   │   ├ ExamGeneratorAgent（可选）→ 测验  │
   │   └ JsonSanitizer 校验 → 持久化        │
   └─────────────────────────────────────────┘
              ↓
   通过 SSE step 事件实时推送：workflow_start → sub_agent → workflow_end
```

### RAG 检索流程（被封装为 `search_knowledge_base` 工具）

```
用户提问 → 查询改写 → 向量检索 + BM25 检索 → RRF 融合 → Cross-encoder 重排序 → 返回片段
```

### 分块策略（策略模式）

系统按优先级自动选择最优分块策略：

1. **MarkdownChunkStrategy** — Markdown 文档（识别标题层级）
2. **HtmlChunkStrategy** — HTML 文档
3. **CodeChunkStrategy** — 代码文件（识别函数 / 类结构）
4. **StructureAwareChunkStrategy** — 结构化文档
5. **LineBasedChunkStrategy** — 行式文档
6. **RecursiveChunkStrategy** — 通用兜底策略

## 数据库设计

| 表名 | 说明 |
|------|------|
| users | 用户信息 |
| documents | 文档元数据 |
| chunks | 分块索引（支持分层 Small-to-Big 检索） |
| embeddings | 向量存储（pgvector） |
| activity_logs | 操作日志 |
| chat_sessions | 聊天会话 |
| chat_messages | 聊天消息（通过 parent_id 构成树形结构） |
| agent_steps | Agent 执行步骤（thinking / tool_call / tool_result / error，final 不入库） |
| exams | 知识测验元信息 |
| exam_context | 测验试题（题干 / 选项 / 答案 / 解析） |
| exam_answers | 用户答题记录与得分 |
| study_plans | 学习计划主表 |
| study_plan_phases | 学习计划阶段 |
| study_plan_progress | 阶段进度 |

## 配置说明

### LLM 提供商

在 `application-dev.yaml` 中配置 `LLM_DEFAULT_PROVIDER`：

- `minimax` — MiniMax
- `deepseek` — DeepSeek（支持 thinking tokens）
- `glm` — 智谱 GLM
- `kimi` — Moonshot Kimi

### Agent 配置（`application.yaml`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agent.skills.path` | 空（classpath） | 技能目录路径，未配置时从 classpath `skills/` 解析 |
| `agent.disclosure.threshold` | 5 | 工具 + 技能总数超过此值启用渐进披露 |
| `agent.tool.timeout-seconds` | 180 | 普通工具执行超时 |
| `agent.tool.workflow-timeout-seconds` | 600 | 工作流类工具（如 `start_study_plan_workflow`）超时 |
| `agent.memory.type` | window | 记忆类型：`window` / `summary` |
| `agent.memory.max-messages` | 40 | 滑动窗口最大消息数 |
| `agent.memory.max-tokens` | 32000 | summary 模式 token 预算 |
| `agent.web-search.tavily.api-key` | - | Tavily API Key |
| `agent.web-search.tavily.max-results` | 5 | 联网搜索返回结果数 |

### 语义缓存（`rag.cache.semantic-cache`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enabled` | true | 是否启用回答级语义缓存 |
| `threshold` | 0.95 | 相似度阈值 |
| `quota-per-user` | 100 | 每用户缓存配额 |
| `quantization` | NOQUANT | Redis Vector Set 量化方式 |

### 文件存储路径

- `RAG_STORE_PATH` — 上传文件存储目录
- `rag.reranker.model-path` — ONNX 重排序模型路径（默认 `classpath:models/ms-marco-MiniLM-L-6-v2/model.onnx`）

## 关键 API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/agent/chat` | POST | SSE 流式对话（推送 thinking / tool_call / tool_result / final 事件） |
| `/agent/workflow/clarify` | POST | HumanInTheLoop 澄清回复，唤醒阻塞的工作流 |
| `/agent/sessions` | GET/POST | 会话列表 / 创建会话 |
| `/agent/sessions/{id}/messages` | GET | 会话消息（树形结构） |
| `/agent/messages/{id}/steps` | GET | 按消息 ID 懒加载 Agent 推理步骤 |
| `/exam` | GET/POST | 测验列表 / 详情 / 提交答案 / 草稿 |
| `/study-plan` | GET/POST | 学习计划列表 / 详情 / 阶段进度更新 / Markdown-HTML 导出 |
| `/rag/ingest` | POST | 文档上传与解析入库 |
| `/rag/search` | POST | 知识库检索 |
| `/user/login` `/user/register` | POST | 登录 / 注册（无需认证） |

> 前端调用时统一加 `/api` 前缀（如 `/api/agent/chat`），由 `vue.config.js` 代理剥离后转发到后端。
