# 题型 JSON Schema 定义

本文档定义知识测验中每种题型的 JSON 结构。LLM 生成试题时必须严格参照对应题型的样板 JSON。

## 通用字段

所有题型共有的字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 题型标识，仅限以下 5 种值 |
| `stem` | string | 是 | 题目文本 |
| `answer` | string 或 array | 是 | 正确答案，格式因题型而异（见下） |
| `explanation` | string | 否（推荐） | 答案解析 |
| `difficulty` | string | 否 | 难度：`easy` / `medium` / `hard`，默认 `medium` |
| `options` | array | 选择题必填 | 选项数组，仅 single_choice 和 multi_choice 需要 |

**注意**：不要传 `id` 字段，题目序号由后端自动生成。

---

## single_choice（单选题）

```json
{
  "type": "single_choice",
  "stem": "以下哪种数据结构最适合实现LRU缓存？",
  "options": [
    "A. 数组",
    "B. 单向链表",
    "C. 哈希表+双向链表",
    "D. 栈"
  ],
  "answer": "C. 哈希表+双向链表",
  "explanation": "哈希表提供O(1)查找，双向链表提供O(1)插入删除，两者结合是经典实现。",
  "difficulty": "medium"
}
```

字段规则：
- `options`：4 个选项，每个以 `A.` `B.` `C.` `D.` 开头，字母连续
- `answer`：**字符串**，必须与 options 中某个元素完全一致（含字母前缀和文本），前端通过字符串比较判分
- 错误写法：`"answer": "C"` — 缺少选项文本，前端无法匹配
- 正确写法：`"answer": "C. 哈希表+双向链表"` — 与 options 元素完全一致

---

## multi_choice（多选题）

```json
{
  "type": "multi_choice",
  "stem": "以下哪些排序算法是稳定排序？",
  "options": [
    "A. 冒泡排序",
    "B. 快速排序",
    "C. 归并排序",
    "D. 堆排序"
  ],
  "answer": ["A. 冒泡排序", "C. 归并排序"],
  "explanation": "冒泡排序和归并排序是稳定排序，相等元素相对顺序不变。快速排序和堆排序不稳定。",
  "difficulty": "medium"
}
```

字段规则：
- `options`：同 single_choice
- `answer`：**字符串数组**，每个元素必须与 options 中某个元素完全一致
- 必须包含 2 个及以上正确选项
- 错误写法：`"answer": ["A", "C"]` — 缺少选项文本
- 错误写法：`"answer": "[\"A\",\"C\"]"` — 字符串而非数组，校验会拒绝
- 正确写法：`"answer": ["A. 冒泡排序", "C. 归并排序"]`

---

## fill_blank（填空题）

```json
{
  "type": "fill_blank",
  "stem": "二叉搜索树的中序遍历结果是一个___序列",
  "answer": "有序",
  "explanation": "二叉搜索树性质：左子树 < 根 < 右子树，中序遍历按从小到大顺序访问所有节点。",
  "difficulty": "easy"
}
```

字段规则：
- `stem`：用 `___`（三个下划线）标记空缺位置
- `answer`：**字符串**，当前前端仅支持单空填空
- 答案应简短精确，不含解释性文字
- 无 `options` 字段

---

## true_false（判断题）

```json
{
  "type": "true_false",
  "stem": "哈夫曼树中不存在度为1的节点",
  "answer": "正确",
  "explanation": "哈夫曼树每次合并两个最小节点，所有非叶子节点都有两个子节点，不存在度为1的节点。",
  "difficulty": "medium"
}
```

字段规则：
- `answer`：**字符串**，仅限 `"正确"` 或 `"错误"`
- 无 `options` 字段（前端自动渲染"正确/错误"两个按钮）

---

## short_answer（简答题）

```json
{
  "type": "short_answer",
  "stem": "简述红黑树的五条基本性质",
  "answer": "1. 节点是红色或黑色；2. 根节点是黑色；3. 所有叶子节点（NIL）是黑色；4. 红色节点的两个子节点都是黑色；5. 从任一节点到其每个叶子的所有路径包含相同数目的黑色节点。",
  "explanation": "红黑树的五条性质保证了树的大致平衡，使得操作时间复杂度为O(log n)。",
  "difficulty": "hard"
}
```

字段规则：
- `answer`：**字符串**，参考答案文本，可包含编号列表
- 无 `options` 字段
- 简答题不做自动判分，前端仅展示参考答案
