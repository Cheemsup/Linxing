---
name: exam
description: "根据用户笔记内容或网络搜索结果，生成结构化知识测验题目。当用户要求出题、测验、测试知识掌握程度时触发。"
tool_names:
  - search_knowledge_base
  - web_search
  - save_exam
  - create_container
  - append_to_container
  - replace_in_container
  - replace_container_metadata
  - remove_from_container
---

# 知识测验技能

根据用户的学习笔记或网络搜索结果，生成结构化的知识测验题目，保存到数据库供用户做题和回顾。

## Quick Reference

| Situation | Action |
|-----------|--------|
| 用户要求基于笔记出题 | `search_knowledge_base` → 生成试题 → `save_exam` |
| 用户要求基于某主题出题（笔记中可能没有） | `web_search` → 生成试题 → `save_exam` |
| 用户要求混合来源出题 | 两个工具都调用，综合素材后生成 |
| 用户未指定来源 | 优先 `search_knowledge_base`，不足时补充 `web_search` |
| 题目数量 ≤ 5 | 一次性调用 `save_exam`，传入完整参数 |
| 题目数量 > 5 | 分批模式：`create_container` → 多次 `append_to_container` → `save_exam(container_id=...)` |

## Background

知识测验是学习闭环中的关键环节——通过主动回忆（active recall）巩固记忆。本技能将用户的学习笔记或网络搜索结果转化为结构化测验题目，支持单选、多选、填空、判断、简答五种题型，保存到数据库后用户可在线做题并查看解析。

核心挑战在于：LLM 生成的 JSON 必须严格符合后端校验规则（尤其是 answer 格式），否则 `save_exam` 会拒绝保存。历史上最常见的失败原因是：①选择题 answer 只写了字母（如 `"C"`）而非完整选项文本（如 `"C. 哈希表+双向链表"`）；②多选题 answer 写成了字符串而非数组；③题目数量多时一次性输出大量 JSON 导致语法错误。分批模式和容器工具的引入正是为了解决第③点。

## Step-by-Step

### 1. 解析用户意图

从用户消息中提取以下信息：

| 信息 | 必需 | 默认值 | 示例 |
|------|------|--------|------|
| 主题/关键词 | 是 | — | "数据结构"、"操作系统" |
| 素材来源 | 否 | notes | "我的笔记"、"网上搜" |
| 题型偏好 | 否 | 混合 | "只出选择题"、"填空题" |
| 题目数量 | 否 | 5 | "出10道题" |
| 难度 | 否 | mixed | "简单题"、"难题" |

如果用户没有明确指定，使用默认值，不要反复追问。

### 2. 收集素材

- 明确说"我的笔记""本地知识库" → `search_knowledge_base`
- 明确说"上网搜索"或笔记中没有的主题 → `web_search`
- 未指定 → 优先 `search_knowledge_base`，不足时补充 `web_search`

素材不足时：笔记返回空 → 建议切换网搜；网搜失败（API Key 未配置等）→ 仅用笔记；两者都失败 → 告知用户无法生成。

### 3. 生成试题

严格按照 `references/question-types.md` 中各题型的样板 JSON 输出。每种题型的必填字段和 answer 格式不同，务必参照对应样板。

**通用规则**：
- 必须输出合法 JSON，不要用 Markdown 代码块包裹
- 每道题必须有 `type`、`stem`、`answer` 字段；`explanation` 为推荐字段（尽量提供）
- 选择题必须有 `options` 数组
- 不要传 `id` 字段，题目序号由后端自动生成
- `answer` 类型必须与题型匹配：单选题/判断题/简答题/填空题必须传字符串；多选题必须传数组
- 单选题/多选题的 `answer` 必须与 `options` 中的某一项完全一致（含字母前缀和文本），不要只写 `"B"` 或 `["B","D"]`

**题型分配建议**（默认 5 题）：单选 2 道、多选 1 道、填空 1 道、判断/简答 1 道。用户指定题型偏好时按偏好调整。

**难度控制**：

| 难度 | 知识点选择 | 干扰项设计 |
|------|-----------|-----------|
| easy | 直接记忆型知识点 | 干扰项明显错误 |
| medium | 需要理解的知识点 | 干扰项有迷惑性 |
| hard | 需要综合应用的知识点 | 干扰项高度相似 |

### 4. 保存测验

根据题目数量选择保存模式：

- **≤ 5 题**：一次性模式，直接调用 `save_exam`
- **> 5 题**：分批模式，先创建容器再分批追加

#### 一次性模式（≤ 5 题）

```json
{
  "title": "数据结构基础测验",
  "source_type": "web_search",
  "questions": [
    {
      "type": "single_choice",
      "stem": "以下哪种数据结构最适合实现LRU缓存？",
      "options": ["A. 数组", "B. 单向链表", "C. 哈希表+双向链表", "D. 栈"],
      "answer": "C. 哈希表+双向链表",
      "explanation": "哈希表提供O(1)查找，双向链表提供O(1)插入删除。",
      "difficulty": "medium"
    }
  ],
  "source_refs": ["https://example.com/data-structures-guide"]
}
```

#### 分批模式（> 5 题）

**4a. 创建容器**

```json
{
  "container_type": "exam",
  "metadata": {
    "title": "高等数学测验",
    "source_type": "knowledge_base",
    "source_refs": ["高数笔记.md"]
  },
  "array_paths": ["questions"]
}
```
→ `{"containerId": "exam_a3f2b1"}`

**4b. 分批追加题目**（每次 1-3 题）

```json
{
  "container_id": "exam_a3f2b1",
  "array_path": "questions",
  "items": [
    { "type": "single_choice", "stem": "...", "options": [...], "answer": "C. ...", "explanation": "..." }
  ]
}
```

**4c. 保存测验**

```json
{ "container_id": "exam_a3f2b1" }
```
→ `{"examId": 123, "questionCount": 15}`

`source_type` 应根据实际使用的工具记录：仅笔记 → `notes`；仅网搜 → `web_search`；两者都用 → `mixed`。**建议传入 `source_refs`**，记录素材来源引用（如笔记文档名、搜索结果 URL），不传时后端会记为空列表。

**校验失败修正**：`save_exam` 返回 `{"success": false, "errors": [{"index": 2, "field": "answer", "message": "..."}]}` 时，用 `replace_in_container` 精确替换错误元素，再重新 `save_exam`。同一元素修正 3 次仍不通过，用 `remove_from_container` 移除（注意索引前移）。**不要盲目重试相同参数**，应先检查并修正错误。

### 5. 返回简短提示

`save_exam` 成功后，告知用户测验已生成并提供链接：`已为您生成《xxx》测验，共 N 道题。[查看测验](/quiz?examId={examId})`。**不要**在回答中重复输出试题内容和答案。

## Common Variations

- **用户要求"只出选择题"**：按用户偏好调整题型分配，但仍建议包含 explanation 字段。单选题建议 4 个选项，多选题建议 2 个及以上正确选项（非强制）。
- **用户要求"基于网搜出题"**：直接调用 `web_search`，`source_type` 记为 `web_search`。网搜结果可能包含噪声，需筛选与主题相关的内容。
- **用户要求"综合笔记和网搜"**：先 `search_knowledge_base`，评估内容是否足够，不足时补充 `web_search`，`source_type` 记为 `mixed`。
- **用户要求大量题目（> 10 题）**：必须使用分批模式，每次追加 1-3 题，避免单次 JSON 过长导致语法错误。
- **素材不足**：笔记和网搜都没有找到足够素材时，告知用户素材不足，建议先导入相关笔记或换一个主题。

## answer 格式规则

| 题型 | answer 格式 | 示例 |
|------|------------|------|
| single_choice | 字符串，与 options 中某项完全一致 | `"C. 哈希表+双向链表"` |
| multi_choice | 字符串数组，每项与 options 中某项完全一致 | `["A. 冒泡排序", "C. 归并排序"]` |
| fill_blank | 字符串，当前前端仅支持单空填空 | `"有序"` |
| true_false | 字符串，仅限 `"正确"` 或 `"错误"` | `"正确"` |
| short_answer | 字符串，参考答案文本 | `"1. 节点是红色或黑色；2. 根节点是黑色..."` |

**关键**：
- 选择题的 answer 必须与 options 中的元素完全一致（含字母前缀和文本），因为前端通过字符串比较判分。**绝对不要**只写 `"B"` 或 `["B","D"]`。
- 单选题/判断题/简答题的 answer 必须是**字符串**，即使是单元素数组（如 `["B"]`）也会被 `save_exam` 拒绝。
- 多选题的 answer 必须是**字符串数组**。**绝对不要**把 multi_choice 的 answer 写成字符串 `"[\"A\",\"C\"]"`，这会导致校验失败。

## Gotchas

- **type 字段仅限 5 种值**：`single_choice`、`multi_choice`、`fill_blank`、`true_false`、`short_answer`。不要使用 `judgment`、`judge`、`boolean` 等非标准名称。
- **选择题 answer 必须与 options 元素完全一致**：如 options 有 `"C. 哈希表+双向链表"`，则 answer 必须是 `"C. 哈希表+双向链表"` 而非 `"C"`。
- **选项格式必须统一**：选择题的 `options` 数组中每个元素必须以 `A.`、`B.` 等字母前缀开头，字母连续不跳过。
- **JSON 语法常见错误**：①字符串值中包含未转义的双引号（用单引号替代）；②对象属性间缺少逗号；③数组/对象末尾多余逗号；④字符串未用双引号包裹。
- **每道题建议包含 explanation**：即使用户没有明确要求，解析也是测验的核心价值，但后端不强制校验。
- **不要编造笔记中没有的知识**：基于笔记出题时，题目必须严格来源于笔记内容。
- **超过 5 题必须使用分批模式**：一次性输出大量题目 JSON 极易出现语法错误。
- **`save_exam` 失败时不要盲目重试**：检查参数中的 JSON 语法，修正后再调用。
- **分批模式校验失败时用 `replace_in_container` 精确修正**：不要重新创建容器。
- **`remove_from_container` 后索引前移**：移除索引 2 的元素后，原索引 3 变为索引 2。

## Related

- `references/question-types.md` — 各题型的完整样板 JSON 和字段规则
- `_shared/references/batch-json-pattern.md` — 分批构建 JSON 的通用模式和容器工具使用指南

## Source

- **Learning ID**: LRN-20250605-EXAM
- **Original Category**: best_practice
- **Extraction Date**: 2025-06-05
