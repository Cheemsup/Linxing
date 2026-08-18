# 本包：Langfuse 观测接入说明

本项目对 Agent 运行的可观测与审计采用「**自定义 `ChatModelListener` + OTel SDK 直连 Langfuse OTLP 端点**」的方式，不采用 langchain4j 原生 Observation / Micrometer 桥接路线

## 一、为什么选这个方式

对照 langchain4j 1.13.0 原生 Observation 桥（`langchain4j-observation → Micrometer → Micrometer Tracing bridge → OTel`）：

1. **input/output 无论如何都要自写**。原生桥只能自动给 model / token / 耗时 / error；Langfuse 需要的 `langfuse.observation.input/output`、agent/tool/sub-agent span、session/user 关联，桥一样给不了，都得手写。桥只省了一小段，却引入两组需联网下载、版本难对齐、两层间接难调试的中间依赖。
2. **调用形态与本项目不匹配**。本项目是自研 ReAct 主循环、自建RAG，接口现状与langfuse官方提供的 AiServices / StreamingChatBuilder 接入教程样例完全不同；而 `ChatModelListener` 在 `LlmManager` 的流式 + 非流式 builder 上一经注册即**全站覆盖**（主循环 + 子 Agent + 离线调用），是更贴合本架构的挂载点。

## 二、接入方式（机制）

一次用户 chat 请求 = 1 条 Trace，含四类业务 span + 每轮 LLM 的 generation span：

```text
Trace
└─ root span: agent-run              ← ChatServiceImpl 入口建 / 出口闭，承载 trace 级字段
   ├─ generation（每轮 LLM 调用）     ← LangfuseChatModelListener（LlmManager 注册）
   ├─ Tool: {toolName}               ← AgentObservability（主循环每次工具调用）
   ├─ Retriever: search_knowledge_base ← SearchServiceImpl（RAG 检索诊断）
   └─ Agent: {agentName}             ← SubAgentStepListener（工作流子 Agent）
```

关键组件：

| 组件 | 职责 |
|---|---|
| `OtelTraceConfig` | 建 `Tracer` Bean + OTLP exporter 直连 Langfuse `/api/public/otel/v1/traces`（Basic auth pk:sk + `x-langfuse-ingestion-version: 4` 头，自动补 `/v1/traces` 路径）；`enabled=false` 返回 no-op |
| `AgentObservability` | 业务 span 建/闭的门面 + trace 级属性**每 span 冗余注入**（官方要求，否则按 user/session 过滤缺数据） |
| `ObservableContext` | ThreadLocal 栈 + `makeCurrent` 跨线程传播 span 引用（agent → tool-exec → 子 Agent 线程） |
| `LangfuseChatModelListener`(+Factory) | generation span；onRequest 建 span 存 attributes map，onResponse/onError 在回调线程取回结束 |
| `MessageSerializer` | 消息 / 响应转 OpenAI 兼容 JSON（图片摘要化不落 base64；input/output 统一截断防超限） |
| `LangfuseAttributeKeys` | 全部 `langfuse.observation.*` / `gen_ai.*` 属性名常量 |

## 三、目前能观测 / 审计什么

| 观测对象 | 内容 | 用途 |
|---|---|---|
| 请求级（root `agent-run`） | session / user / request_id / question / answer / tags / version / environment | 按用户、会话过滤与审计整次请求 |
| LLM 调用（generation） | 模型、provider、输入输出全文、token 用量、耗时、温度、thinking 长度、step_number、重试（每次 attempt 一个 span，失败 ERROR / 成功 OK）、错误信息 | 模型调用审计、token 成本、重试与排障分析 |
| 工具调用（`Tool: xxx`） | 工具名、参数、结果、成功/失败、耗时、tool_kind（function/skill/workflow） | 工具使用审计、异常定位 |
| RAG 检索（`Retriever:`） | query/topK、向量/BM25 候选数、reranker、归一化分数、阈值过滤前后数量、是否命中 | 检索质量诊断（对应根 README 的 langfuse 截图） |
| 子 Agent（`Agent: xxx`） | 子 Agent 输入、全量输出（plan/exam JSON）、role，内部 generation 挂其下 | 工作流审计、产出回放 |

成本：usage tokens 已写入 generation span，由 **Langfuse 控制台 model 定价表** 自动计算（本项目为自定义/中转模型，需在控制台配一次定价；`cost_details` 字段代码侧预留未写）。

## 四、边界（当前不观测）

- 离线 LLM 调用（RAG 语义增强 / 摘要 / Memory Worker 后台）默认不入 trace（`langfuse.trace-offline-calls=false`）
- 子 Agent 内部工具调用只产生 step 事件（无独立 `Tool:` span）；其内部 RAG 由 `Retriever:` span 覆盖
- 官方写端仅识别 `langfuse.observation.type=span/generation/event`，tool/agent/retriever 一律写 `span`，语义靠 `Tool:` / `Agent:` / `Retriever:` 命名前缀 + `metadata.kind` 表达

## 五、配置与开关

见根 README「配置说明 → Langfuse 观测」：`langfuse.enabled`（默认 false，零开销）、`endpoint`、`public-key` / `secret-key`、`trace-offline-calls`；可通过环境变量 `LANGFUSE_ENABLED` / `LANGFUSE_ENDPOINT` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` 注入。