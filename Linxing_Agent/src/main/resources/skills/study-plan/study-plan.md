---
name: study_plan
description: "根据用户学习意向、知识库笔记或网络搜索结果，生成结构化分阶段学习计划，可选生成关联测验。当用户要求制定学习计划、规划学习路径、安排学习进度时触发。"
tool_names:
  - search_knowledge_base
  - web_search
  - start_study_plan_workflow
---

# 学习计划制定技能

根据用户的学习意向，结合知识库笔记和/或网络搜索结果，通过多 Agent 工作流生成结构化的分阶段学习计划，可选同时生成关联测验。工作流内部自动处理计划生成、JSON 校验、持久化和测验出题，无需手动保存。

## Quick Reference

| Situation | Action |
|-----------|--------|
| 用户要求基于笔记制定计划 | `start_study_plan_workflow`（工作流内部自主搜索知识库） |
| 用户要求基于某主题制定计划 | `start_study_plan_workflow`（工作流内部自主联网搜索） |
| 用户要求混合来源制定计划 | `start_study_plan_workflow`（工作流内部综合搜索） |
| 用户信息不足（缺目标/时长） | `start_study_plan_workflow(needs_clarification=true)` |
| 用户同时要求生成测验 | `start_study_plan_workflow(generate_exam=true)` |

> **注**：`materials` 参数现为可选。工作流内部的知识收集阶段（KnowledgeCollectorAgent）会自主调用 `searchKnowledgeBase` 和 `webSearch` 工具收集素材。若 LLM 已通过主循环搜索到素材并传入 `materials`，则作为已有素材补充使用。

## Background

学习计划制定是学习闭环的起点。本技能通过 `start_study_plan_workflow` 工具触发后端多 Agent 工作流，工作流采用**两阶段顺序编排**：

**阶段一：知识收集**
1. **澄清提问**（可选）：当 `needs_clarification=true` 时，工作流暂停等待用户回复（仅一次打断机会）
2. **知识收集**：KnowledgeCollectorAgent 自主调用搜索工具（知识库 + 联网）收集素材，写入 AgenticScope 的 `materials`

**阶段二：内容生成**
1. **计划生成**：PlanGeneratorAgent 基于阶段一收集的素材生成结构化学习计划 JSON
2. **测验生成**（可选）：当 `generate_exam=true` 时，ExamGeneratorAgent 根据计划内容出题
3. **持久化**：解析 JSON 并保存到数据库，计划与测验通过 `linked_plan_id` 关联

两阶段通过 `sequenceBuilder` 顺序编排，确保"最终 plan/exam 生成一定要建立在前一阶段充分收集知识内容和用户情况之后"。工作流通过 SSE step 事件实时推送进度（`workflow_start` → `sub_agent` → `workflow_end`）。

## Step-by-Step

### 1. 解析用户意图

从用户消息中提取以下信息：

| 信息 | 必需 | 默认值 | 示例 |
|------|------|--------|------|
| 学习主题 | 是 | — | "Rust"、"机器学习"、"操作系统" |
| 学习目标 | 否 | 掌握主题核心知识 | "从零到能写项目"、"应付面试" |
| 总时长 | 否 | 根据主题推断 | "3个月"、"半年" |
| 素材来源 | 否 | notes | "我的笔记"、"网上搜" |
| 是否需要测验 | 否 | false | "顺便出点题"、"生成测验" |

**判断是否需要澄清**：如果用户只说了主题但缺少目标或时长，且这些信息对计划质量有重大影响，设置 `needs_clarification=true` 并提供 `clarification_question`。不要为小事反复追问。

### 2. 收集素材（可选）

> **重要变更**：`materials` 参数现为可选。工作流内部的知识收集阶段会自主搜索收集素材，LLM 无需在主循环中预先搜索。

- 若 LLM 已通过主循环搜索到素材（如用户明确要求"先用我的笔记"），可将素材摘要传入 `materials`，工作流会作为已有素材补充使用
- 若 `materials` 为空，工作流的 KnowledgeCollectorAgent 会自主调用 `searchKnowledgeBase`（知识库）和 `webSearch`（联网）收集素材
- `source_type` 根据实际来源设置：`notes` / `web_search` / `mixed` / `none`

### 3. 调用工作流

调用 `start_study_plan_workflow`，传入以下参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| topic | string | 是 | 学习主题 |
| goal | string | 否 | 学习目标 |
| duration | string | 否 | 学习时长，如"3个月" |
| materials | string | 否 | 素材内容（笔记摘要、搜索结果等） |
| source_type | string | 否 | 素材来源：notes/web_search/mixed/none，默认 none |
| generate_exam | boolean | 否 | 是否生成测验，默认 false |
| needs_clarification | boolean | 否 | 是否需要向用户澄清，默认 false |
| clarification_question | string | 否 | 澄清问题（needs_clarification=true 时必填） |

#### 场景 A：信息充足，直接生成

```json
{
  "topic": "Rust",
  "goal": "从零到能写项目",
  "duration": "3个月",
  "materials": "Rust 是一门系统编程语言...",
  "source_type": "web_search",
  "generate_exam": false,
  "needs_clarification": false
}
```

#### 场景 B：信息不足，需要澄清

```json
{
  "topic": "机器学习",
  "needs_clarification": true,
  "clarification_question": "您希望学习时长是多少？有没有特定目标（如面试、科研、项目应用）？您的编程基础如何？"
}
```

工作流会暂停并通过 SSE 推送 `sub_agent` 事件（含 `question` 字段），前端展示输入框等待用户回复。用户回复后工作流继续执行。

#### 场景 C：同时生成测验

```json
{
  "topic": "操作系统",
  "goal": "应付面试",
  "duration": "2个月",
  "materials": "进程线程、内存管理、文件系统...",
  "source_type": "mixed",
  "generate_exam": true,
  "needs_clarification": false
}
```

### 4. 处理工作流结果

工作流返回结果包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| planSaved | boolean | 计划是否保存成功 |
| examSaved | boolean | 测验是否保存成功 |
| examTriggered | boolean | 是否触发了测验生成 |
| clarificationTriggered | boolean | 是否触发了澄清提问 |
| clarificationTimedOut | boolean | 澄清是否超时（用户未在 25 分钟内回复） |
| planRetryCount | int | 计划生成重试次数（0 表示一次成功） |
| planId | int | 计划 ID（保存成功时返回） |
| examId | int | 测验 ID（保存成功时返回） |
| phaseCount | int | 阶段数量 |
| questionCount | int | 题目数量 |
| error | string | 错误信息（失败时返回） |
| planParseError | string | 计划解析错误详情（清洗后仍失败时返回） |
| examParseError | string | 测验解析错误详情（清洗后仍失败时返回） |

**成功后告知用户**：`已为您生成《xxx》学习计划，共 N 个阶段。[查看计划](/study-plan?planId={planId})`。如果同时生成了测验，附加：`已生成关联测验（N 题），[去测验](/exam?examId={examId})`。**不要**在回答中重复输出完整计划内容。

**澄清超时提示**：如果 `clarificationTimedOut=true`，告知用户：`由于未收到您的补充信息，已基于现有信息生成初版计划，如需调整可随时告诉我。`

**失败处理**：如果 `planSaved=false`，检查 `error` 字段，向用户说明失败原因。如果 `examSaved=false` 但 `planSaved=true`，告知用户计划已生成但测验生成失败。

## generate_exam 规则

- 用户明确要求"出题""测验""考试""练习题" → `generate_exam=true`
- 用户说"顺便考考我""看看掌握没" → `generate_exam=true`
- 用户只要求学习计划，未提及测验 → `generate_exam=false`
- 测验基于计划内容生成，题目数量由 ExamGeneratorAgent 根据计划规模自动决定
- 测验通过 `linked_plan_id` 关联到计划，可在计划详情页查看

## needs_clarification 规则

- 用户只提供了主题，缺少目标和时长 → 可设为 `true`
- 用户提供了完整信息（主题+目标+时长） → 设为 `false`
- 用户信息模糊但不影响计划生成 → 设为 `false`，用默认值
- `clarification_question` 应一次性问完所有缺失信息，不要多次追问
- 工作流暂停等待用户回复，超时（25 分钟）后自动使用"无补充信息"继续
- 澄清超时后生成的计划标记为"初版"（`clarificationTimedOut=true`），用户可基于初版再请求优化
- 澄清期间同一 session 重复注册会自动取消旧的 pending 请求，避免状态错乱

## Common Variations

- **用户要求"基于笔记制定计划"**：直接调用 `start_study_plan_workflow`，工作流内部 KnowledgeCollectorAgent 会优先搜索知识库。若需明确指定，可在 `materials` 传入笔记摘要，`source_type` 记为 `notes`。
- **用户要求"基于网搜制定计划"**：直接调用 `start_study_plan_workflow`，工作流内部会联网搜索。`source_type` 记为 `web_search`。
- **用户要求"综合笔记和网搜"**：直接调用 `start_study_plan_workflow`，工作流内部会综合搜索知识库和联网。`source_type` 记为 `mixed`。
- **用户要求"制定计划并出题"**：调用 `start_study_plan_workflow(generate_exam=true)`。
- **用户信息不足**：调用 `start_study_plan_workflow(needs_clarification=true, clarification_question="...")`，工作流暂停等待用户回复后继续。
- **用户未指定时长**：根据主题复杂度推断，如"学 Rust"建议 3 个月，"学机器学习"建议 6 个月。不需要为此澄清。

## Gotchas

- **不要手动生成计划 JSON**：工作流内部的 PlanGeneratorAgent 负责生成，你只需传入素材和参数。
- **不要调用 save_study_plan**：该工具已被 `start_study_plan_workflow` 替代，工作流自动持久化。
- **materials 现为可选**：工作流内部的知识收集阶段会自主搜索。若 LLM 已有素材可传入作为补充，但无需强制预先搜索。
- **clarification_question 要具体**：不要问"您想学什么？"，应问"您希望学习时长是多少？有没有特定目标？"
- **source_type 要准确**：根据实际使用的工具记录，影响后端素材引用追溯。
- **工作流超时**：澄清等待 25 分钟超时后自动继续，用户未回复时使用"无补充信息"，生成的计划标记为"初版"。
- **测验生成失败不影响计划**：工作流先保存计划再生成测验，测验失败时计划已保存。
- **仅一次澄清机会**：工作流设计中只保留一次打断提问机会，避免反复追问影响用户体验。

## Failure Handling

工作流对 JSON 解析与持久化采用分层容错策略，按以下优先级处理：

1. **提示词硬约束**：PlanGeneratorAgent / ExamGeneratorAgent 的提示词明确要求"首字符为 `{`、末字符为 `}`、禁止 Markdown 代码块、禁止前后解释文字"，从源头降低格式错误概率。
2. **JsonSanitizer 清洗**：`persistResults` 在解析前对所有 Agent 输出做清洗——去除 ```json 围栏、按字符串/转义感知匹配最外层 `{...}`、截断尾部多余文字。即便 LLM 偶尔违反格式约束，清洗后仍可正常解析。
3. **计划与测验独立容错**：计划解析失败时标记 `planSaved=false` 并记录错误信息，不影响工作流结束；测验解析失败时计划已保存，仅标记 `examSaved=false`，用户可单独重试测验。
4. **澄清超时降级**：澄清等待超时后以"无补充信息"继续生成，结果标记为"初版"，用户可基于初版再请求优化，无需从头开始。

## Related

- 计划详情页：`/study-plan?planId={planId}`
- 测验详情页：`/exam?examId={examId}`
- 澄清回复端点：`POST /agent/workflow/clarify`（前端自动调用，无需 LLM 参与）

## Source

- **Learning ID**: LRN-20250613-STUDY-PLAN
- **Original Category**: best_practice
- **Extraction Date**: 2025-06-13
