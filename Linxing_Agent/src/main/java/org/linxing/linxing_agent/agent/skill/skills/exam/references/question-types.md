# 题型 JSON Schema 定义

本文档定义知识测验输出的完整 JSON Schema，LLM 生成试题时必须严格遵循。

## 顶层结构

```json
{
  "title": "string — 测验标题，如'数据结构测验'",
  "questions": "array — 题目数组，至少1个元素"
}
```

## 题型一：single_choice（单选题）

```json
{
  "id": "q1",
  "type": "single_choice",
  "stem": "以下哪种数据结构最适合实现LRU缓存？",
  "options": [
    "A. 数组",
    "B. 单向链表",
    "C. 哈希表 + 双向链表",
    "D. 栈"
  ],
  "answer": "C. 哈希表 + 双向链表",
  "explanation": "LRU缓存需要O(1)的查找和删除能力。哈希表提供O(1)查找，双向链表提供O(1)插入删除，两者结合是经典实现。",
  "difficulty": "medium"
}
```

字段规则：
- `id`：格式为 `q` + 数字，从 q1 递增
- `type`：固定为 `"single_choice"`
- `stem`：题目文本，以问号结尾
- `options`：4个选项，每个以 `A.` `B.` `C.` `D.` 开头，字母连续
- `answer`：必须与 options 中某个选项完全一致（含字母前缀）
- `explanation`：必填，解释为什么该答案正确
- `difficulty`：可选，`easy` / `medium` / `hard`

## 题型二：multi_choice（多选题）

```json
{
  "id": "q2",
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
- `type`：固定为 `"multi_choice"`
- `answer`：**必须是数组**，包含2个及以上正确选项
- 其余字段同 single_choice

## 题型三：fill_blank（填空题）

```json
{
  "id": "q3",
  "type": "fill_blank",
  "stem": "二叉搜索树的中序遍历结果是一个___序列",
  "answer": "有序",
  "explanation": "二叉搜索树性质：左子树 < 根 < 右子树，中序遍历按从小到大顺序访问所有节点。",
  "difficulty": "easy"
}
```

字段规则：
- `type`：固定为 `"fill_blank"`
- `stem`：用 `___`（三个下划线）标记空缺位置
- `answer`：简短精确的词或短语，不含解释
- 无 `options` 字段

## 题型四：true_false（判断题）

```json
{
  "id": "q4",
  "type": "true_false",
  "stem": "哈夫曼树中不存在度为1的节点",
  "answer": "正确",
  "explanation": "哈夫曼树每次合并两个最小节点，所有非叶子节点都有两个子节点，不存在度为1的节点。",
  "difficulty": "medium"
}
```

字段规则：
- `type`：固定为 `"true_false"`
- `answer`：只能是 `"正确"` 或 `"错误"`
- 无 `options` 字段

## 题型五：short_answer（简答题）

```json
{
  "id": "q5",
  "type": "short_answer",
  "stem": "简述红黑树的五条基本性质",
  "answer": "1. 节点是红色或黑色；2. 根节点是黑色；3. 所有叶子节点（NIL）是黑色；4. 红色节点的两个子节点都是黑色；5. 从任一节点到其每个叶子的所有路径包含相同数目的黑色节点。",
  "explanation": "红黑树的五条性质保证了树的大致平衡，使得操作时间复杂度为O(log n)。",
  "difficulty": "hard"
}
```

字段规则：
- `type`：固定为 `"short_answer"`
- `answer`：参考答案文本，可包含编号列表
- 无 `options` 字段
- 简答题不做自动判分，前端仅展示参考答案

## 完整示例

```json
{
  "title": "数据结构与算法测验",
  "questions": [
    {
      "id": "q1",
      "type": "single_choice",
      "stem": "以下哪种数据结构最适合实现LRU缓存？",
      "options": ["A. 数组", "B. 单向链表", "C. 哈希表 + 双向链表", "D. 栈"],
      "answer": "C. 哈希表 + 双向链表",
      "explanation": "LRU缓存需要O(1)查找和删除能力。",
      "difficulty": "medium"
    },
    {
      "id": "q2",
      "type": "multi_choice",
      "stem": "以下哪些排序算法是稳定排序？",
      "options": ["A. 冒泡排序", "B. 快速排序", "C. 归并排序", "D. 堆排序"],
      "answer": ["A. 冒泡排序", "C. 归并排序"],
      "explanation": "冒泡排序和归并排序是稳定排序。",
      "difficulty": "medium"
    },
    {
      "id": "q3",
      "type": "fill_blank",
      "stem": "二叉搜索树的中序遍历结果是一个___序列",
      "answer": "有序",
      "explanation": "中序遍历按从小到大顺序访问。",
      "difficulty": "easy"
    },
    {
      "id": "q4",
      "type": "true_false",
      "stem": "哈夫曼树中不存在度为1的节点",
      "answer": "正确",
      "explanation": "哈夫曼树所有非叶子节点都有两个子节点。",
      "difficulty": "medium"
    },
    {
      "id": "q5",
      "type": "short_answer",
      "stem": "简述红黑树的五条基本性质",
      "answer": "1. 节点是红色或黑色；2. 根节点是黑色；3. 所有叶子节点是黑色；4. 红色节点的子节点都是黑色；5. 任一节点到叶子的路径黑色节点数相同。",
      "explanation": "五条性质保证树的平衡。",
      "difficulty": "hard"
    }
  ]
}
```
