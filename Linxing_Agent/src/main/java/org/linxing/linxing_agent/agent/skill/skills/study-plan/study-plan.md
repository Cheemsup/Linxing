---
name: study_plan
description: "根据用户学习意向、知识库笔记或网络搜索结果，生成结构化分阶段学习计划。当用户要求制定学习计划、规划学习路径、安排学习进度时触发。"
tool_names:
  - search_knowledge_base
  - web_search
  - save_study_plan
  - create_container
  - append_to_container
  - replace_in_container
  - replace_container_metadata
  - remove_from_container
---

# 学习计划制定技能

根据用户的学习意向，结合知识库笔记和/或网络搜索结果，生成结构化的分阶段学习计划，保存到数据库供用户查看、追踪进度和导出。

## Quick Reference

| Situation | Action |
|-----------|--------|
| 用户要求基于笔记制定计划 | `search_knowledge_base` → 生成计划 → `save_study_plan` |
| 用户要求基于某主题制定计划（笔记中可能没有） | `web_search` → 生成计划 → `save_study_plan` |
| 用户要求混合来源制定计划 | 两个工具都调用，综合素材后生成 |
| 用户未指定来源 | 优先 `search_knowledge_base`，不足时补充 `web_search` |
| 阶段数 ≤ 5 | 一次性调用 `save_study_plan`，传入完整参数 |
| 阶段数 > 5 | 分批模式：`create_container` → 多次 `append_to_container` → `save_study_plan(container_id=...)` |

## Background

学习计划制定是学习闭环的起点——将模糊的学习意向转化为可执行的分阶段计划。本技能将用户的学习意向、知识库笔记或网络搜索结果转化为结构化的学习计划，包含多个阶段，每个阶段包含关键知识点、学习资源、实践任务和里程碑。保存到数据库后用户可在线查看、标记阶段进度、导出为 Markdown/HTML。

核心挑战在于：LLM 生成的 JSON 必须严格符合后端校验规则（尤其是 phases 数组结构），否则 `save_study_plan` 会拒绝保存。历史上最常见的失败原因是：①缺少必填字段 title 或 goal；②phases 数组为空或缺少 title；③阶段数过多时一次性输出大量 JSON 导致语法错误。分批模式和容器工具的引入正是为了解决第③点。

## Step-by-Step

### 1. 解析用户意图

从用户消息中提取以下信息：

| 信息 | 必需 | 默认值 | 示例 |
|------|------|--------|------|
| 学习主题 | 是 | — | "Rust"、"机器学习"、"操作系统" |
| 学习目标 | 否 | 掌握主题核心知识 | "从零到能写项目"、"应付面试" |
| 总时长 | 否 | 根据主题推断 | "3个月"、"半年" |
| 素材来源 | 否 | notes | "我的笔记"、"网上搜" |
| 阶段数 | 否 | 根据时长推断 | "分5个阶段"、"按月划分" |
| 难度/基础 | 否 | 从零开始 | "有编程基础"、"零基础" |

如果用户没有明确指定，使用默认值，不要反复追问。从用户消息中提取"X个月"、"X周"等时长信息，以及"从零到..."等目标信息。

### 2. 收集素材

- 明确说"我的笔记""本地知识库" → `search_knowledge_base`
- 明确说"上网搜索"或笔记中没有的主题 → `web_search`
- 未指定 → 优先 `search_knowledge_base`，不足时补充 `web_search`

素材不足时：笔记返回空 → 建议切换网搜；网搜失败（API Key 未配置等）→ 仅用笔记；两者都失败 → 基于通用知识生成（`source_type` 记为 `web_search`）。

### 3. 生成学习计划

严格按照下方的 JSON 结构输出。每个阶段必须包含 `title`，推荐包含 `objective`、`key_topics`、`resources`、`practice_tasks`、`milestones`。

**通用规则**：
- 必须输出合法 JSON，不要用 Markdown 代码块包裹
- 计划必须有 `title`、`goal`、`phases` 字段
- 每个 phase 必须有 `title` 字段
- 不要传 `id` 字段，阶段序号由后端自动生成
- `phases` 数组不能为空
- `key_topics`、`practice_tasks`、`milestones` 必须是字符串数组
- `resources` 可以是字符串数组或对象数组（含 name/url）
- `source_type` 必须是 `notes`/`web_search`/`mixed` 之一

**阶段划分建议**：
- 按时间划分：如"3个月计划"分为 3-4 个阶段，每阶段约 1 个月
- 按难度递进：基础 → 进阶 → 实战 → 综合
- 每个阶段应包含明确的目标、关键知识点、学习资源、实践任务和里程碑
- 阶段数建议 3-8 个，过多则使用分批模式

**资源推荐原则**：
- 优先推荐官方文档、经典书籍、优质开源项目
- 资源应与阶段主题匹配，避免泛泛而谈
- 实践任务应可执行、可验证，避免"多练习"这类模糊描述
- 里程碑应是可衡量的成果，如"能独立写出 XXX"

### 4. 保存学习计划

根据阶段数量选择保存模式：

- **≤ 5 阶段**：一次性模式，直接调用 `save_study_plan`
- **> 5 阶段**：分批模式，先创建容器再分批追加

#### 一次性模式（≤ 5 阶段）

```json
{
  "title": "Rust 3个月学习计划",
  "goal": "从零到能写项目",
  "description": "面向有编程基础的开发者，3个月掌握 Rust 并能独立完成项目",
  "duration": "3个月",
  "source_type": "web_search",
  "phases": [
    {
      "title": "第1月：基础语法",
      "duration": "4周",
      "objective": "掌握 Rust 基础语法和所有权机制",
      "key_topics": ["变量与类型", "所有权机制", "借用与引用", "结构体与枚举"],
      "resources": [
        {"name": "The Rust Book", "url": "https://doc.rust-lang.org/book/"},
        {"name": "Rust by Example", "url": "https://doc.rust-lang.org/rust-by-example/"}
      ],
      "practice_tasks": ["实现一个CLI计算器", "完成Exercism前10题"],
      "milestones": ["能独立写出Hello World", "理解所有权规则"]
    }
  ],
  "source_refs": ["https://doc.rust-lang.org/book/"]
}
```

#### 分批模式（> 5 阶段）

**4a. 创建容器**

```json
{
  "container_type": "study_plan",
  "metadata": {
    "title": "全栈开发6个月学习计划",
    "goal": "从零成为全栈工程师",
    "description": "覆盖前端、后端、数据库、部署的完整学习路径",
    "duration": "6个月",
    "source_type": "mixed"
  },
  "array_paths": ["phases"]
}
```
→ `{"containerId": "study_plan_a3f2b1"}`

**4b. 分批追加阶段**（每次 1-3 个）

```json
{
  "container_id": "study_plan_a3f2b1",
  "array_path": "phases",
  "items": [
    {
      "title": "第1月：HTML/CSS基础",
      "duration": "4周",
      "objective": "掌握网页结构与样式",
      "key_topics": ["HTML5语义化", "CSS布局", "响应式设计"],
      "practice_tasks": ["实现一个个人主页"],
      "milestones": ["能独立完成静态网页"]
    }
  ]
}
```

**4c. 保存学习计划**

```json
{ "container_id": "study_plan_a3f2b1" }
```
→ `{"planId": 456, "phaseCount": 8}`

`source_type` 应根据实际使用的工具记录：仅笔记 → `notes`；仅网搜 → `web_search`；两者都用 → `mixed`。**建议传入 `source_refs`**，记录素材来源引用（如笔记文档名、搜索结果 URL），不传时后端会记为空列表。

**校验失败修正**：`save_study_plan` 返回 `{"success": false, "errors": [{"index": 2, "field": "title", "message": "..."}]}` 时，用 `replace_in_container` 精确替换错误元素，再重新 `save_study_plan`。同一元素修正 3 次仍不通过，用 `remove_from_container` 移除（注意索引前移）。**不要盲目重试相同参数**，应先检查并修正错误。

### 5. 返回简短提示

`save_study_plan` 成功后，告知用户学习计划已生成并提供链接：`已为您生成《xxx》学习计划，共 N 个阶段。[查看计划](/study-plan?planId={planId})`。**不要**在回答中重复输出完整计划内容。

## Common Variations

- **用户要求"基于笔记制定计划"**：先 `search_knowledge_base` 查询相关笔记，根据笔记内容规划阶段。`source_type` 记为 `notes`。
- **用户要求"基于网搜制定计划"**：直接 `web_search`，`source_type` 记为 `web_search``。网搜结果可能包含噪声，需筛选与主题相关的内容。
- **用户要求"综合笔记和网搜"**：先 `search_knowledge_base`，评估内容是否足够，不足时补充 `web_search`，`source_type` 记为 `mixed`。
- **用户要求大量阶段（> 8 阶段）**：必须使用分批模式，每次追加 1-3 个阶段，避免单次 JSON 过长导致语法错误。
- **用户指定了明确时长**：按时长划分阶段，如"6个月"分为 6 个阶段或 3 个双月阶段。
- **用户未指定时长**：根据主题复杂度推断，如"学 Rust"建议 3 个月，"学机器学习"建议 6 个月。
- **素材不足**：笔记和网搜都没有找到足够素材时，可基于通用知识生成计划，`source_type` 记为 `web_search`。

## JSON 字段规则

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 是 | 计划标题，如"Rust 3个月学习计划" |
| goal | string | 是 | 学习目标，如"从零到能写项目" |
| description | string | 否 | 计划描述或背景说明 |
| duration | string | 否 | 计划总时长，如"3个月" |
| source_type | string | 是 | 素材来源：notes/web_search/mixed |
| source_refs | array | 否 | 素材来源引用列表 |
| phases | array | 是 | 学习阶段数组，不能为空 |

**阶段（phase）字段规则**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 是 | 阶段标题，如"第1月：基础语法" |
| duration | string | 否 | 阶段时长，如"4周" |
| objective | string | 否 | 阶段学习目标（推荐填写） |
| key_topics | string[] | 否 | 关键知识点数组 |
| resources | array | 否 | 学习资源数组，元素可为字符串或 {name, url} 对象 |
| practice_tasks | string[] | 否 | 实践任务数组 |
| milestones | string[] | 否 | 阶段里程碑数组 |

**关键**：
- `phases` 数组不能为空，每个 phase 必须有 `title`。
- `key_topics`、`practice_tasks`、`milestones` 必须是字符串数组，不要传对象。
- `resources` 可以是字符串数组（如 `["The Rust Book"]`）或对象数组（如 `[{"name":"The Rust Book","url":"https://..."}]`）。
- 不要传 `id`、`phase_order` 等字段，后端自动生成。

## Gotchas

- **title 和 goal 是必填字段**：缺少任一字段 `save_study_plan` 会拒绝保存。
- **phases 数组不能为空**：至少包含 1 个阶段，每个阶段必须有 title。
- **数组字段必须是数组**：`key_topics`、`resources`、`practice_tasks`、`milestones` 若提供则必须是数组，不能是字符串或对象。
- **JSON 语法常见错误**：①字符串值中包含未转义的双引号；②对象属性间缺少逗号；③数组/对象末尾多余逗号；④字符串未用双引号包裹。
- **每个阶段建议包含 objective 和 practice_tasks**：即使用户没有明确要求，实践任务也是学习计划的核心价值，但后端不强制校验。
- **不要编造不存在的资源 URL**：推荐资源时应使用已知可靠的官方文档、经典书籍，不确定 URL 时只给名称。
- **超过 5 阶段必须使用分批模式**：一次性输出大量阶段 JSON 极易出现语法错误。
- **`save_study_plan` 失败时不要盲目重试**：检查参数中的 JSON 语法，修正后再调用。
- **分批模式校验失败时用 `replace_in_container` 精确修正**：不要重新创建容器。
- **`remove_from_container` 后索引前移**：移除索引 2 的元素后，原索引 3 变为索引 2。
- **阶段应循序渐进**：基础 → 进阶 → 实战，不要把高级内容放在第一阶段。

## Related

- `_shared/references/batch-json-pattern.md` — 分批构建 JSON 的通用模式和容器工具使用指南

## Source

- **Learning ID**: LRN-20250613-STUDY-PLAN
- **Original Category**: best_practice
- **Extraction Date**: 2025-06-13
