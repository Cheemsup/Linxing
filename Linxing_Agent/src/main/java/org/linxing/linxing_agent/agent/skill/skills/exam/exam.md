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

根据用户的学习笔记或网络搜索结果，生成结构化的知识测验题目，输出可被前端解析渲染为可交互试题页面的 JSON 数据。

## Quick Reference

| Situation | Action |
|-----------|--------|
| 用户要求基于笔记出题 | 调用 `search_knowledge_base` 获取笔记内容，再生成试题 |
| 用户要求基于某主题出题（笔记中可能没有） | 调用 `web_search` 搜索相关内容，再生成试题 |
| 用户要求混合来源出题 | 同时调用两个工具，综合素材后生成试题 |
| 用户未指定来源 | 优先使用 `search_knowledge_base`，不足时补充 `web_search` |
| 题目数量 ≤ 5 | 一次性调用 `save_exam`，传入完整参数 |
| 题目数量 > 5 | 使用分批模式：`create_container` → 多次 `append_to_container` → `save_exam(container_id=...)` |

## Background

用户在学习过程中需要检验知识掌握程度。传统方式是手动整理题目，效率低下。本技能通过 LLM 自动从笔记或网络素材中提取关键知识点，生成多种题型的结构化测验，并保存到数据库供用户随时回顾和重做。

核心挑战：
- LLM 输出的试题必须是合法 JSON，且结构严格一致，否则前端无法解析渲染
- 不同题型（单选/多选/填空/判断/简答）的 JSON 结构各不相同，需要精确定义
- 素材来源可能是笔记或网搜，需要灵活选择工具

## Solution

### Step-by-Step

1. **确认素材来源**：判断用户需求是"基于笔记"还是"基于外部知识"
   - 明确说"我的笔记""本地知识库""RAG知识库"→ `search_knowledge_base`
   - 明确说"上网搜索"或笔记中没有的主题 → `web_search`
   - 未指定 → 优先 `search_knowledge_base`，不足时补充 `web_search`

2. **收集素材**：调用对应工具获取内容片段

3. **生成试题**：严格按照 `references/question-types.md` 中定义的 JSON Schema 输出
   - 必须输出合法 JSON，不要输出 Markdown 代码块包裹
   - 每道题必须有 `id`、`type`、`stem`、`answer`、`explanation` 字段
   - 选择题必须有 `options` 数组

4. **保存测验**：根据题目数量选择保存模式

   **判断规则**：
   - 题目数量 ≤ 5：使用**一次性模式**，直接调用 `save_exam` 传入完整参数
   - 题目数量 > 5：使用**分批模式**，先创建容器再分批追加

   #### 一次性模式（≤ 5 题）

   传入完整的测验 JSON（含 title、source_type、questions 数组），必须传入 `source_refs` 参数，记录素材来源引用（如笔记文档名、搜索结果URL），例如：`["RAG搭建笔记.md", "https://example.com/rag-guide"]`

   **`save_exam` 调用示例**（必须严格参照此格式传参）：

   ```json
   {
     "title": "数据结构基础测验",
     "source_type": "web_search",
     "questions": [
       {
         "type": "single_choice",
         "stem": "以下哪种数据结构最适合实现LRU缓存？",
         "options": ["A. 数组", "B. 单向链表", "C. 哈希表+双向链表", "D. 栈"],
         "answer": "C",
         "explanation": "哈希表提供O(1)查找，双向链表提供O(1)插入删除。",
         "difficulty": "medium"
       },
       {
         "type": "multi_choice",
         "stem": "以下哪些是栈的合法应用场景？（多选）",
         "options": ["A. 函数调用栈", "B. 括号匹配", "C. 二叉树遍历", "D. 队列模拟"],
         "answer": ["A", "B", "C"],
         "explanation": "函数调用、括号匹配和二叉树遍历（中序非递归）都使用栈。",
         "difficulty": "medium"
       }
     ],
     "source_refs": ["https://example.com/data-structures-guide"]
   }
   ```

   #### 分批模式（> 5 题）

   分批模式将"一次性输出完整 JSON"拆分为"创建容器 + 分批追加 + 保存"三步，降低单次输出长度，减少 JSON 语法错误。

   **步骤 4a：创建容器**

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
   返回：`{"containerId": "exam_a3f2b1"}`

   **步骤 4b：分批追加题目**（每次 2-3 题）

   ```json
   {
     "container_id": "exam_a3f2b1",
     "array_path": "questions",
     "items": [
       {
         "type": "single_choice",
         "stem": "第一题题干...",
         "options": ["A. ...", "B. ...", "C. ...", "D. ..."],
         "answer": "B",
         "explanation": "解析...",
         "difficulty": "medium"
       },
       {
         "type": "multi_choice",
         "stem": "以下哪些是RAG的优势？（多选）",
         "options": ["A. 无需重训模型", "B. 可引用来源", "C. 完全消除幻觉", "D. 处理私有数据"],
         "answer": ["A", "B", "D"],
         "explanation": "解析...",
         "difficulty": "hard"
       }
     ]
   }
   ```
   返回：`{"containerId": "exam_a3f2b1", "arrayPath": "questions", "currentCount": 2, "appendedCount": 2}`

   继续追加直到所有题目添加完毕。

   **步骤 4c：保存测验**

   ```json
   {
     "container_id": "exam_a3f2b1"
   }
   ```
   返回：`{"examId": 123, "questionCount": 15}`

   **校验失败时的修正流程**：

   如果 `save_exam(container_id=...)` 返回校验错误（如 `{"success": false, "errors": [{"index": 2, "field": "answer", "message": "..."}]}`），使用 `replace_in_container` 精确修正错误元素：

   ```json
   {
     "container_id": "exam_a3f2b1",
     "array_path": "questions",
     "index": 2,
     "item": { "type": "multi_choice", "stem": "...", "options": [...], "answer": ["A","C"], "explanation": "..." }
   }
   ```

   修正后重新调用 `save_exam(container_id=...)`。

   如果同一元素修正 3 次仍不通过，使用 `remove_from_container` 移除该元素（注意：移除后后续元素索引前移）：

   ```json
   {
     "container_id": "exam_a3f2b1",
     "array_path": "questions",
     "index": 2
   }
   ```

   **关键约束**：
   - **`answer` 格式规则**：
     - `multi_choice`：传数组，如 `"answer": ["A","C"]`
     - `fill_blank`：单空传字符串如 `"answer": "TCP"`，多空传数组如 `"answer": ["Retrieval-Augmented", "Generation"]`
     - 其余题型（single_choice / true_false / short_answer）：传字符串，如 `"answer": "B"` 或 `"answer": "正确"`
     - **绝对不要**把 multi_choice 的 answer 写成字符串 `"[\"A\",\"C\"]"`，这会导致校验失败
   - `options` 字段：仅选择题需要，填空/判断/简答不要传此字段
   - `stem` 和 `explanation` 中的文本不要包含未转义的双引号，如需引用请用单引号
   - 确保 JSON 语法正确：所有字符串用双引号包裹、属性间用逗号分隔、无尾逗号
   - 分批追加时每次建议 2-3 题，避免单次输出过长

5. **返回简短提示**：`save_exam` 成功后，只需告知用户测验已生成并提供链接，格式如"已为您生成《xxx》测验，共 N 道题。[查看测验](/quiz?examId={examId})"。**不要**在回答中重复输出试题内容和答案，答案不应在聊天界面暴露。

### JSON Output 格式

输出的 JSON 必须严格遵循以下顶层结构（详细字段定义见 `references/question-types.md`）：

```json
{
  "title": "测验标题",
  "questions": [
    { "id": "q1", "type": "single_choice", ... },
    { "id": "q2", "type": "fill_blank", ... }
  ]
}
```

**重要**：不要在 JSON 外面加 Markdown 代码块标记（```），直接输出纯 JSON 字符串。

## Common Variations

- **仅出某种题型**：用户说"只出选择题"→ 只生成 `single_choice` 和 `multi_choice` 类型的题目
- **指定难度**：用户说"出简单题"→ 侧重基础知识点，`difficulty` 设为 `easy`
- **指定题数**：用户说"出10道题"→ 按要求数量生成，默认5道
- **基于特定文档**：用户指定文档名 → 在 `search_knowledge_base` 的查询中包含文档名

## Gotchas

- **type 字段仅限以下5种值**：`single_choice`、`multi_choice`、`fill_blank`、`true_false`、`short_answer`。不要使用 `judgment`、`judge`、`boolean` 等非标准名称，否则保存会失败。
- **JSON 格式错误是致命的**：如果输出不是合法 JSON，前端将无法解析，用户只能看到原始文本。务必确保 JSON 语法正确。
- **JSON 语法常见错误**：①字符串值中包含未转义的双引号（用单引号替代）；②对象属性间缺少逗号；③数组/对象末尾多余逗号；④字符串未用双引号包裹。调用 `save_exam` 前务必在内部检查 JSON 语法。
- **选项格式必须统一**：选择题的 `options` 数组中每个元素必须以 "A."、"B." 等字母前缀开头，且字母连续不跳过。
- **answer 字段格式**：`multi_choice` 传数组如 `["A","C"]`；`fill_blank` 单空传字符串如 `"TCP"`，多空传数组如 `["Retrieval-Augmented","Generation"]`；其余题型传字符串。**绝对不要**把 multi_choice 的 answer 写成字符串 `"[\"A\",\"C\"]"`。
- **填空题答案要精确**：`fill_blank` 的 `answer` 应该是简短精确的词或短语，不要包含解释性文字。
- **每道题必须有 explanation**：即使用户没有明确要求，解析也是测验的核心价值。
- **不要编造笔记中没有的知识**：基于笔记出题时，题目必须严格来源于笔记内容，不能自行发挥。
- **Prompt 中必须包含 "json" 关键词**：DeepSeek 的 JSON Output 模式要求 prompt 中出现 "json" 一词，否则可能返回空内容。
- **`save_exam` 调用失败时不要反复重试相同参数**：如果 `save_exam` 返回 JSON 解析失败，说明参数格式有误。应仔细检查参数中的 JSON 语法（引号闭合、逗号分隔、无尾逗号），修正后再调用，而非盲目重试。
- **超过5题必须使用分批模式**：一次性输出大量题目 JSON 极易出现语法错误，分批模式每次只输出 2-3 题，大幅降低出错概率。
- **分批模式校验失败时使用 replace_in_container 精确修正**：`save_exam(container_id=...)` 校验失败会返回索引级错误（index + field + message），根据错误信息用 `replace_in_container` 替换指定索引的元素，不要重新创建容器。
- **remove_from_container 后索引前移**：移除索引 2 的元素后，原索引 3 的元素变为索引 2，后续 replace/remove 操作需注意索引变化。

## Related

- `references/question-types.md` — 各题型的完整 JSON Schema 定义和示例（Phase 3 按需加载）
- `references/exam-workflow.md` — 详细的工作流说明和异常处理（Phase 3 按需加载）

## Source

- **Learning ID**: LRN-20250605-EXAM
- **Original Category**: best_practice
- **Extraction Date**: 2025-06-05
