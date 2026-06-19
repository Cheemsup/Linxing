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
| 用户要求基于笔记制定计划 | `search_knowledge_base` → `start_study_plan_workflow` |
| 用户要求基于某主题制定计划 | `web_search` → `start_study_plan_workflow` |
| 用户要求混合来源制定计划 | 两个工具都调用，综合素材后 `start_study_plan_workflow` |
| 用户信息不足（缺目标/时长） | `start_study_plan_workflow(needs_clarification=true)` |
| 用户同时要求生成测验 | `start_study_plan_workflow(generate_exam=true)` |

## Background

学习计划制定是学习闭环的起点。本技能通过 `start_study_plan_workflow` 工具触发后端多 Agent 工作流，工作流内部依次执行：

1. **澄清提问**（可选）：当 `needs_clarification=true` 时，工作流暂停等待用户回复
2. **计划生成**：PlanGeneratorAgent 根据主题、目标、素材生成结构化学习计划 JSON
3. **测验生成**（可选）：当 `generate_exam=true` 时，ExamGeneratorAgent 根据计划内容出题

工作流自动处理 JSON 校验和数据库持久化，通过 SSE step 事件实时推送进度（`workflow_start` → `sub_agent` → `workflow_end`）。计划保存后返回 `planId`，测验保存后返回 `examId`，两者通过 `linked_plan_id` 关联。

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

### 2. 收集素材

- 明确说"我的笔记""本地知识库" → `search_knowledge_base`
- 明确说"上网搜索"或笔记中没有的主题 → `web_search`
- 未指定 → 优先 `search_knowledge_base`，不足时补充 `web_search`

将搜索到的素材内容整理后传入 `materials` 参数。`source_type` 根据实际来源设置：`notes` / `web_search` / `mixed` / `none`。

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
| planId | int | 计划 ID（保存成功时返回） |
| examId | int | 测验 ID（保存成功时返回） |
| phaseCount | int | 阶段数量 |
| questionCount | int | 题目数量 |
| error | string | 错误信息（失败时返回） |

**成功后告知用户**：`已为您生成《xxx》学习计划，共 N 个阶段。[查看计划](/study-plan?planId={planId})`。如果同时生成了测验，附加：`已生成关联测验（N 题），[去测验](/exam?examId={examId})`。**不要**在回答中重复输出完整计划内容。

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
- 工作流暂停等待用户回复，超时（120秒）后自动使用"无补充信息"继续

## Common Variations

- **用户要求"基于笔记制定计划"**：先 `search_knowledge_base` 查询相关笔记，将笔记内容传入 `materials`，`source_type` 记为 `notes`。
- **用户要求"基于网搜制定计划"**：直接 `web_search`，将搜索结果传入 `materials`，`source_type` 记为 `web_search`。
- **用户要求"综合笔记和网搜"**：两个工具都调用，综合素材后传入 `materials`，`source_type` 记为 `mixed`。
- **用户要求"制定计划并出题"**：正常收集素材后调用 `start_study_plan_workflow(generate_exam=true)`。
- **用户信息不足**：调用 `start_study_plan_workflow(needs_clarification=true, clarification_question="...")`，工作流暂停等待用户回复后继续。
- **用户未指定时长**：根据主题复杂度推断，如"学 Rust"建议 3 个月，"学机器学习"建议 6 个月。不需要为此澄清。

## Gotchas

- **不要手动生成计划 JSON**：工作流内部的 PlanGeneratorAgent 负责生成，你只需传入素材和参数。
- **不要调用 save_study_plan**：该工具已被 `start_study_plan_workflow` 替代，工作流自动持久化。
- **clarification_question 要具体**：不要问"您想学什么？"，应问"您希望学习时长是多少？有没有特定目标？"
- **materials 要精炼**：传入素材摘要即可，不要传入完整的搜索结果原文，避免 token 浪费。
- **source_type 要准确**：根据实际使用的工具记录，影响后端素材引用追溯。
- **工作流超时**：澄清等待 120 秒超时后自动继续，用户未回复时使用"无补充信息"。
- **测验生成失败不影响计划**：工作流先保存计划再生成测验，测验失败时计划已保存。

## Related

- 计划详情页：`/study-plan?planId={planId}`
- 测验详情页：`/exam?examId={examId}`
- 澄清回复端点：`POST /agent/workflow/clarify`（前端自动调用，无需 LLM 参与）

## Source

- **Learning ID**: LRN-20250613-STUDY-PLAN
- **Original Category**: best_practice
- **Extraction Date**: 2025-06-13
