---
name: exam
description: "根据用户笔记内容或网络搜索结果，生成结构化知识测验题目。当用户要求出题、测验、测试知识掌握程度时触发。"
tool_names:
  - search_knowledge_base
  - web_search
  - save_exam
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

4. **保存测验**：调用 `save_exam` 工具将测验持久化到数据库
   - 传入完整的测验 JSON（含 title、source_type、questions 数组）
   - 必须传入 `source_refs` 参数，记录素材来源引用（如笔记文档名、搜索结果URL），例如：`["RAG搭建笔记.md", "https://example.com/rag-guide"]`
   - 工具返回 `{"examId": 123}`
   - 在最终回答中引用 examId，告知用户测验已生成

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
         "type": "true_false",
         "stem": "二叉搜索树的中序遍历结果是有序序列",
         "answer": "正确",
         "explanation": "BST性质保证中序遍历按从小到大顺序访问。",
         "difficulty": "easy"
       }
     ],
     "source_refs": ["https://example.com/data-structures-guide"]
   }
   ```

   **关键约束**：
   - `answer` 字段：单选/判断/填空/简答传字符串（如 `"C"` 或 `"正确"`）；多选传字符串数组（如 `["A","C"]`）
   - `options` 字段：仅选择题需要，填空/判断/简答不要传此字段
   - `stem` 和 `explanation` 中的文本不要包含未转义的双引号，如需引用请用单引号
   - 确保 JSON 语法正确：所有字符串用双引号包裹、属性间用逗号分隔、无尾逗号

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
- **answer 字段格式因题型而异**：`multi_choice` 类型的 `answer` 为字符串数组，如 `["A","C"]`；其余题型为字符串，如 `"C"` 或 `"正确"`。不要在非多选题中使用数组格式的 answer。
- **填空题答案要精确**：`fill_blank` 的 `answer` 应该是简短精确的词或短语，不要包含解释性文字。
- **每道题必须有 explanation**：即使用户没有明确要求，解析也是测验的核心价值。
- **不要编造笔记中没有的知识**：基于笔记出题时，题目必须严格来源于笔记内容，不能自行发挥。
- **Prompt 中必须包含 "json" 关键词**：DeepSeek 的 JSON Output 模式要求 prompt 中出现 "json" 一词，否则可能返回空内容。
- **`save_exam` 调用失败时不要反复重试相同参数**：如果 `save_exam` 返回 JSON 解析失败，说明参数格式有误。应仔细检查参数中的 JSON 语法（引号闭合、逗号分隔、无尾逗号），修正后再调用，而非盲目重试。

## Related

- `references/question-types.md` — 各题型的完整 JSON Schema 定义和示例（Phase 3 按需加载）
- `references/exam-workflow.md` — 详细的工作流说明和异常处理（Phase 3 按需加载）

## Source

- **Learning ID**: LRN-20250605-EXAM
- **Original Category**: best_practice
- **Extraction Date**: 2025-06-05
