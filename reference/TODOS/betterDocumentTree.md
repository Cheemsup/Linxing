# 文档树形结构改进计划

## 一、前端展示优化

### 1.1 类型筛选功能

**功能描述：** 支持在导航树中按 chunk 类型筛选节点，如只显示代码块、只显示表格、只显示章节标题等。

**字段分析：**

`chunkType` 字段在完整数据链路中的传递：`chunks.chunk_type (DB)` → [Chunk.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/entity/Chunk.java#L31) `.chunkType` → [DocumentServiceImpl.toChunkTreeVO()](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/service/impl/DocumentServiceImpl.java#L239-L249) → [ChunkTreeVO.chunkType](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/vo/ChunkTreeVO.java#L18) → API JSON → [ChunkTreeNode.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNode.vue#L13) `.chunk-type-badge`

**结论：字段已完整贯穿全链路，无需任何后端/数据库改造。**

**已有类型值：** `section`, `general`, `code`, `table`, `qa_pair`, `context_weak`

**实施方案：**

1. **纯前端改造** —— 在 [ChunkTreeNav.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNav.vue) 的 `tree-header` 下方添加类型筛选栏
   - UI: 水平排列的 chip/tag 按钮（全部 / 标题 / 段落 / 代码 / 表格 / 问答 / 弱上下文）
   - 状态: `data()` 中新增 `activeTypeFilter: null`（null = 全部）
   - 逻辑: 通过 prop 向下透传 `activeTypeFilter` 至 [ChunkTreeNode.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNode.vue)，节点根据 `node.chunkType` 决定是否渲染

2. **树结构保持完整性** —— 避免"父节点被过滤但子节点保留"导致的孤立节点：
   - 方案A（推荐）: 不隐藏不匹配的节点，而是将其半透明/置灰（`opacity: 0.3`），保留树结构
   - 方案B: 彻底隐藏不匹配节点，但若某父节点的任一子节点匹配，则强制显示该父节点（递归向上展露路径）

3. **无需后端变更** —— `ChunkTreeVO` 已包含 `chunkType`，API `/documents/{id}/chunk-tree` 返回数据无需改变

---

## 二、树形结构增强

### 2.1 保持原始文件的内容顺序

**功能描述：** 确保导航树中节点（章节/段落）的显示顺序与原始文档中出现的顺序完全一致。

> **详细根因分析和 sort_order 方案验证见 [第四章](#四分块排序修复--核心问题分析2026-05-03-新增)。**

**本节补充 sort_order 方案的关键验证结论：**

#### 2.1.1 排序字段设计决策：全局递增 vs 层级独立

| 方案 | 示例（以 Markdown 文档2个章节为例） | 排序SQL | 树构造 |
|---|---|---|---|
| **全局递增**（推荐） | L1_章1=1, L2_章1子1=2, L2_章1子2=3, L1_章2=4, L2_章2子1=5 | `ORDER BY sort_order ASC` 一条查询还原完整顺序 | 从 `allChunks` 中 filter `parent_chunk_id IS NULL` 得到根节点列表，顺序正确；children 从 `groupingBy` 结果获取，Stream 保证 encounter order |
| 层级独立 | L1_章1=1, L2_章1子1=1, L2_章1子2=2, L1_章2=2, L2_章2子1=1 | `ORDER BY chunk_level, sort_order` 需要二列组合 | 同上，但数据语义不如全局方案直观 |

**选择全局递增的理由：** 单列排序语义清晰；与原始文档的"阅读顺序"一一对应；树构造逻辑无需改变。

#### 2.1.2 所有6种 chunk 策略的文档顺序保真度验证

| 策略 | 文件 | 分块方式 | 是否严格保序 | 备注 |
|---|---|---|---|---|
| MarkdownChunkStrategy | [MarkdownChunkStrategy.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/MarkdownChunkStrategy.java) | 按标题拆分 → 线性遍历 | ✅ 是 | 超长 section 先插 L1 再插 L2 children，完全按文档遍历顺序 |
| StructureAwareChunkStrategy | [StructureAwareChunkStrategy.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/StructureAwareChunkStrategy.java) | 按 `\n{2,}` 段落分隔 → 线性遍历 | ✅ 是 | 全 L2 无父子关系，纯线性 |
| CodeChunkStrategy | [CodeChunkStrategy.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/CodeChunkStrategy.java) | 正则匹配 class/func → `matches.sort(start)` 显式排序 → 线性遍历 | ✅ 是 | 有显式 `start` 位置排序，最可靠 |
| LineBasedChunkStrategy | [LineBasedChunkStrategy.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/LineBasedChunkStrategy.java) | 按空行分隔 → 线性遍历 | ✅ 是 | 全 L2，纯线性 |
| RecursiveChunkStrategy | [RecursiveChunkStrategy.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/RecursiveChunkStrategy.java) | LangChain4j `DocumentSplitters.recursive()` | ✅ 是 | LangChain4j 库保证文档顺序 |
| HtmlChunkStrategy | [HtmlChunkStrategy.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/HtmlChunkStrategy.java) | 按 h1-h6 / section 标签 → 线性遍历 | ✅ 是 | 超长 section 先插 L1 再插 L2 children，同 Markdown 模式 |
| SemanticChunkStrategy ⚠️ | [SemanticChunkStrategy.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/SemanticChunkStrategy.java) | LLM 返回 JSON `{start, end, summary}` 数组，按返回顺序遍历 | ⚠️ 理论风险 | LLM 返回顺序通常与文档顺序一致，但无强制保证；**且 `supports()` 返回 `false`，当前不自动激活** |

**结论：** 6 种策略全部按文档遍历顺序产出 `List<ChunkResult>`。全局递增 `sort_order` 能正确还原文档顺序。唯一理论风险点在 SemanticChunkStrategy 的 LLM 返回顺序，但该策略当前不会自动激活。

#### 2.1.3 Pass 3 管线后处理不影响排序

[ChunkPipelineCoordinator Pass 3](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/pipeline/ChunkPipelineCoordinator.java#L125-L133) 仅执行 `pipeline.execute(pCtx)` + `chunkMapper.update(chunk)` —— 更新字段为 `title_path`、`ts_content`、`chunk_type` 等，**不修改 sort_order**，因此不破坏排序。

#### 2.1.4 树构造逻辑与 sort_order 的配合验证

[DocumentServiceImpl.getChunkTree()](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/service/impl/DocumentServiceImpl.java#L213-L242) 的关键逻辑：

```
allChunks ──SQL ORDER BY sort_order ASC──▶ 按文档顺序的有序列表
    │
    ├── filter parent_chunk_id IS NULL ──▶ level1Chunks (保持Stream顺序 = 文档顺序)
    │       └── for each level1 ──▶ childrenMap.get(level1.id) (保持groupingBy encounter order = 文档顺序)
    │
    └── fallback: 无Level1时 ──▶ 直接平铺 allChunks (文档顺序)
```

✅ 树构造逻辑与 sort_order 完全兼容，无需修改 `getChunkTree()` 方法体。

---

### 2.2 节点顺序号

**功能描述：** 在导航树的每个节点旁显示其在同级节点中的位置序号（如 "1.", "2.", "3."）。

**字段分析：**

此功能直接依赖 `sort_order`（见 [第四章](#四分块排序修复--核心问题分析2026-05-03-新增)）。`sort_order` 是全局递增的，而节点顺序号需要在**同级**（同 `parent_chunk_id`）内重新编号。

**实施方案：**

1. **后端** —— 在 [DocumentServiceImpl.toChunkTreeVO()](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/service/impl/DocumentServiceImpl.java#L239-L249) 中为 children 列表的每个节点计算同级序号：
   ```java
   // 构建 children 时附加序号
   List<Chunk> children = childrenMap.getOrDefault(level1.getId(), List.of());
   for (int idx = 0; idx < children.size(); idx++) {
       ChunkTreeVO childVO = toChunkTreeVO(children.get(idx));
       childVO.setSiblingIndex(idx + 1);
       // ...
   }
   ```
2. **VO** —— [ChunkTreeVO.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/vo/ChunkTreeVO.java) 新增 `private Integer siblingIndex;`
3. **前端** —— [ChunkTreeNode.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNode.vue) 在 `.node-title` 前展示 `{{ node.siblingIndex }}.`
4. **依赖** —— 必须先完成 sort_order 改造（第五章），因为同级节点的顺序依赖 sort_order 保证

---

### 2.3 状态持久化

**功能描述：** 用户展开/折叠树节点的状态保存到 localStorage，下次打开同一文档的导航树时恢复。

**方案：**

- 存储 key: `chunk-tree-state:{documentId}`
- 值: `Set<chunkId>` —— 记录所有已折叠的节点 ID
- 在 [ChunkTreeNode.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNode.vue) 的 `toggleExpand()` 中读写 localStorage
- 首次加载时默认全部展开

**无需后端变更。**

---

## 三、导航弹窗化改造（2026-05-03 新增）

### 3.1 问题描述

当前点击文档的 "🌳 导航" 按钮后，`ChunkTreeNav` 组件以**侧边面板**形式从右侧滑出，宽度仅 320px，空间极为局促。而同一页面中点击 "👁 预览" 按钮后，`DocumentPreview` 组件是以**居中弹窗**（overlay modal）形式呈现，空间充裕（max-width: 900px, height: 85vh）。

**现状路径分析：**

| 组件 | 文件 | 展示方式 | 尺寸 |
|---|---|---|---|
| [DocumentPreview.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/DocumentPreview.vue) | 预览 | 居中弹窗 overlay | max-width: 900px, height: 85vh |
| [ChunkTreeNav.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNav.vue) | 导航 | 侧边栏 | width: 320px, height: 100% |

**根因：** [ChunkTreeNav.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNav.vue#L88-L95) 样式中将自身定义为 `width: 320px; border-left: 1px solid #e0e0e0; height: 100%` 的侧边面板，缺乏遮罩层和居中定位。在 [NotesPanel.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/NotesPanel.vue#L97-L102) 中直接作为兄弟元素渲染在 `.notes-panel-wrapper` 内部，与主面板并排显示。

### 3.2 实施方案

#### 步骤 1：改造 [ChunkTreeNav.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNav.vue) 为弹窗模式

1. **添加遮罩层** `.tree-nav-overlay`：fixed 定位覆盖全屏，半透明黑色背景，click 自身关闭
2. **添加弹窗容器** `.tree-nav-container`：居中定位，白色背景，圆角，阴影
   - max-width: 700px、max-height: 80vh、width: 90%
3. **保留内部结构**：tree-header / tree-loading / tree-empty / tree-content 不变
4. **移除旧样式**：删除 `.chunk-tree-nav` 中的 `width: 320px; border-left; height: 100%` 等侧边栏样式
5. **支持键盘关闭**：监听 Escape 键触发 `$emit('close')`
6. **添加 footer**：可选的底栏显示节点统计信息（如 "共 N 个节点"）

#### 步骤 2：确保 [NotesPanel.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/NotesPanel.vue) 中渲染方式不变

由于 `ChunkTreeNav` 自身变为 overlay 模式，`NotesPanel.vue` 中的引用方式无需改动（仍然是 `v-if="showTreeNav"` 条件渲染），但需确认 z-index 层级不冲突：
- DocumentPreview z-index: 1000
- ChunkTreeNav z-index: 1001（略高于预览）

#### 步骤 3：适配移动端

- 小屏幕下 `.tree-nav-container` 使用 `width: 95vw; max-height: 90vh`
- 节点点击区域适当增大触摸目标

---

## 四、分块排序修复 —— 核心问题分析（2026-05-03 新增）

### 4.1 问题描述

导航树中章节/段落的显示顺序与原始文件中的内容顺序不一致，表现为：原文件前部的章节在导航树中排到了后部章节的后面。

### 4.2 根因深度分析

#### 4.2.1 数据库表结构缺失排序字段

查看 [chunks 表 DDL](file:///d:/JavaProjects/Linxing/reference/project_design/Tables_in_DB.md)，当前 `chunks` 表**没有 `sort_order` 或 `sequence` 字段**：

```sql
CREATE TABLE chunks(
    id SERIAL NOT NULL,
    user_id integer NOT NULL,
    document_id integer NOT NULL,
    parent_chunk_id integer,
    chunk_level smallint DEFAULT 1,
    chunk_text text NOT NULL,
    -- ... 其他字段 ...
    created_at timestamp with time zone DEFAULT now(),
    PRIMARY KEY(id)
);
-- ❌ 缺少 sort_order 列
```

#### 4.2.2 当前排序依赖脆弱的隐式假设

[ChunkMapper.xml](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/resources/mapper/ChunkMapper.xml#L97-L101) 中的 `findByDocumentIdOrdered` 查询：

```sql
SELECT ... FROM chunks
WHERE document_id = #{documentId}
ORDER BY chunk_level ASC, parent_chunk_id ASC, id ASC
```

该排序依赖以下**隐式假设**：

1. **假设 Level 1 块按文档顺序插入** —— 因为 [ChunkPipelineCoordinator.processDocument()](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/pipeline/ChunkPipelineCoordinator.java#L97-L103) 的 Pass 1 循环按 `results` 列表顺序（文档顺序）插入 Level 1 块
2. **假设 Level 2 块按文档顺序插入** —— Pass 2 同理按 `results` 列表顺序插入
3. **假设 `id` 自增单调递增且严格反映插入顺序** —— 在单事务内插入时通常成立，但依赖数据库实现

#### 4.2.3 哪些场景会打破假设

| 场景 | 风险等级 | 说明 |
|---|---|---|
| 修改 pipeline 插入顺序 | **高** | 若未来重构 Pass 1/2 的循环方式，排序立即失效 |
| 多事务插入 | **中** | 若 chunk 插入跨多个事务，`id` 自增间隙可能打乱顺序 |
| 策略创建的 `results` 非严格文档顺序 | **低** | 当前各策略均按文档顺序产出 results，但无强制约束 |
| 后续复用/重新分块 | **中** | 若同一文档重新分块，新旧 chunk ID 交错可能导致顺序混乱 |

#### 4.2.4 HashMap 迭代顺序隐患

[ChunkPipelineCoordinator.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/pipeline/ChunkPipelineCoordinator.java#L96) 中：

```java
Map<Integer, Integer> resultIndexToDbId = new HashMap<>();
```

Loop A 使用 `resultIndexToDbId.entrySet()` 迭代 —— **HashMap 不保证迭代顺序**。虽然这在当前代码中仅影响 Pass 3 的 `allChunks` 收集（不影响最终的 SQL 排序结果），但一旦顺序依赖链有变动，此处就会成为隐患。

#### 4.2.5 实际触发问题的可能场景

最典型场景：当某个策略（如 [StructureAwareChunkStrategy](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/impl/StructureAwareChunkStrategy.java)）为 docx/pdf 生成**全部 Level 2 块（无 Level 1 父块）**时，所有块 `parent_chunk_id = NULL`、`chunk_level = 2`。

此时 SQL 排序退化为 `ORDER BY id ASC`。如果文档较长且被分割为多个 sections，理论上 ID 顺序应该等于文档顺序。但在以下情况会出错：

- **跨文档的场景**：若两个文档同时处理，ID 不再严格代表同一文档内的顺序
- **子段拆分**：`refinementPipeline.refine()` 将超长 section 拆分为多个子块时，子块的 ID 顺序是否与原文子段顺序严格一致完全取决于 SQL INSERT 的执行顺序（在 for 循环中,JDBC 驱动通常保证顺序，但这也是隐式依赖）

#### 4.2.6 全流程验证：仅添加 `sort_order` 列是否足以保证文档顺序正确？

> **直接回答：是。** 前提是在 Pipeline 中以原始 `results` 列表的遍历顺序全局递增赋值 `sort_order`（方案见 4.3 步骤 3）。

以下是逐层验证链路（从文档输入 → 策略分块 → Pipeline 插入 → SQL 查询 → 树构造 → API 返回）：

```
阶段1: 策略层 — 6种策略全部按文档顺序产出 List<ChunkResult>
┌─────────────────────────────────────────────────────────────────┐
│ 策略                  │ 分块方式                        │ 保序？  │
├─────────────────────────────────────────────────────────────────┤
│ MarkdownChunkStrategy  │ 按标题正则匹配 → 从文档头到尾线性遍历     │ ✅ 是   │
│ StructureAwareStrategy │ 按段落分隔 split → 数组线性遍历          │ ✅ 是   │
│ CodeChunkStrategy      │ 正则匹配 → matches.sort(start)显式排序   │ ✅ 是   │
│ HtmlChunkStrategy      │ 按h1-h6/section标签 → 线性遍历           │ ✅ 是   │
│ LineBasedChunkStrategy │ 按空行 split → 数组线性遍历              │ ✅ 是   │
│ RecursiveChunkStrategy │ LangChain4j递归拆分 → 库保证顺序         │ ✅ 是   │
│ SemanticChunkStrategy  │ LLM返回JSON→按返回顺序遍历（理论风险）    │ ⚠️ 但supports()=false │
├─────────────────────────────────────────────────────────────────┤
│ 关键点: 所有策略的 results 列表顺序 ≡ 文档原始顺序                  │
│ 注: Markdown/Html 策略超长 section 的 L1→L2 children 插入顺序      │
│     也完全遵循文档顺序（section1的L1和L2都在section2之前）          │
└─────────────────────────────────────────────────────────────────┘
                                    ↓
阶段2: Pipeline层 — 按 results 顺序全局递增赋值 sort_order
┌─────────────────────────────────────────────────────────────────────┐
│ sortOrderCounter=0                                                  │
│ Pass 1 (按 results 索引 i=0→N-1 遍历):                              │
│   results[0] L1_章节1 → sort_order=1                                │
│   results[3] L1_章节2 → sort_order=2                                │
│ Pass 2 (按 results 索引 i=0→N-1 遍历):                              │
│   results[1] L2_章节1子1 (parent=0) → sort_order=3                  │
│   results[2] L2_章节1子2 (parent=0) → sort_order=4                  │
│   results[4] L2_章节2子1 (parent=3) → sort_order=5                  │
│                                                                     │
│ 结果: sort_order 1,3,4 属于章节1; sort_order 2,5 属于章节2          │
│ 文档顺序: 章节1→子1→子2→章节2→子1 ✅                               │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
阶段3: SQL层 — ORDER BY sort_order ASC 单列排序
┌─────────────────────────────────────────────────────────────────────┐
│ SELECT * FROM chunks WHERE document_id=? ORDER BY sort_order ASC    │
│                                                                     │
│ 返回: [L1_ch1(1), L1_ch2(2), L2_ch1_1(3), L2_ch1_2(4), L2_ch2_1(5)]│
│                                                                     │
│ 无需 chunk_level, parent_chunk_id, id 参与排序 ✅                    │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
阶段4: Service层 — getChunkTree() 树构造
┌─────────────────────────────────────────────────────────────────────┐
│ allChunks (按sort_order有序)                                        │
│   → filter parent_chunk_id IS NULL → [L1_ch1, L1_ch2] (保序)       │
│   → childrenMap = groupingBy(parent_chunk_id)                       │
│       → {1: [L2_ch1_1, L2_ch1_2], 2: [L2_ch2_1]}                  │
│       → Stream groupingBy 保持 encounter order                      │
│                                                                     │
│ 构建树:                                                             │
│   ├── L1_ch1 (sort=1)                                               │
│   │   ├── L2_ch1_1 (sort=3) ← children按sort_order有序              │
│   │   └── L2_ch1_2 (sort=4)                                        │
│   └── L1_ch2 (sort=2)                                               │
│       └── L2_ch2_1 (sort=5)                                        │
│  ✅ 树节点顺序 = 文档原始顺序                                        │
└─────────────────────────────────────────────────────────────────────┘
                                    ↓
阶段5: API层 — ChunkTreeVO JSON 返回前端
┌─────────────────────────────────────────────────────────────────────┐
│ JSON数组保持Java List顺序 → 前端 v-for 渲染保持顺序 → ✅            │
└─────────────────────────────────────────────────────────────────────┘
```

**验证结论：**

1. ✅ 全局递增 `sort_order` 足以保证从策略产出到前端渲染的全链路顺序正确
2. ✅ 不需要添加第二个排序字段（如层级独立的 `level_order`）
3. ✅ 不需要修改 SQL 排序为多列组合（`ORDER BY sort_order ASC` 即可）
4. ⚠️ `childrenMap` 虽为 HashMap（key 无序），但 get(key) 返回的 `List<Chunk>` 由 `groupingBy` 保证 encounter order（即 sort_order 顺序）
5. ⚠️ `resultIndexToDbId` 需改用 `LinkedHashMap` 以消除 Pass 3 的迭代顺序隐患（见 4.3 步骤 3）
6. ✅ 现有 `getChunkTree()` 方法体无需修改

**唯一理论风险点：** SemanticChunkStrategy 的 LLM 返回顺序无强制约束，但该策略当前 `supports()` 返回 `false`，不会被自动选中。若未来启用，可在 `parseResponse()` 中对 LLM 返回的 segments **按 `start` 升序排序**来规避此风险。

### 4.3 实施方案

#### 步骤 1：数据库添加 `sort_order` 列

```sql
ALTER TABLE chunks ADD COLUMN sort_order integer DEFAULT 0 NOT NULL;
COMMENT ON COLUMN chunks.sort_order IS '分块在文档中的原始顺序序号，从0开始递增';
CREATE INDEX idx_chunks_doc_sort ON chunks(document_id, sort_order);
```

#### 步骤 2：修改 Entity 和 Mapper XML

**文件：** [Chunk.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/entity/Chunk.java)
- 新增字段：`private Integer sortOrder;`

**文件：** [ChunkMapper.xml](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/resources/mapper/ChunkMapper.xml)
- BaseResultMap 添加 `<result column="sort_order" property="sortOrder"/>`
- BaseColumnList 添加 `sort_order`
- INSERT 语句添加 `sort_order` 字段
- `findByDocumentIdOrdered` 查询改为：`ORDER BY sort_order ASC`

#### 步骤 3：修改 Pipeline 在插入时设置 sort_order

**文件：** [ChunkPipelineCoordinator.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/pipeline/ChunkPipelineCoordinator.java)

关键改动：

```java
// 维护一个递增计数器
int sortOrderCounter = 0;

// Pass 1: Level 1 块插入
for (int i = 0; i < results.size(); i++) {
    ChunkResult r = results.get(i);
    if (r.getChunkLevel() == CHUNK_LEVEL_1) {
        Chunk chunk = buildChunk(r, doc, null);
        chunk.setSortOrder(++sortOrderCounter);  // ← 新增
        chunkMapper.insert(chunk);
        resultIndexToDbId.put(i, chunk.getId());
    }
}

// Pass 2: Level 2 块插入
for (int i = 0; i < results.size(); i++) {
    ChunkResult r = results.get(i);
    if (r.getChunkLevel() != CHUNK_LEVEL_1) {
        Chunk chunk = buildChunk(r, doc, parentDbId);
        chunk.setSortOrder(++sortOrderCounter);  // ← 新增
        chunkMapper.insert(chunk);
        allChunks.add(chunk);
    }
}
```

**重要设计决策：** `sort_order` 是**全局递增**（跨 Level 1 和 Level 2 统一编号），而不是每个层级独立编号。这样单次 `ORDER BY sort_order ASC` 就能还原完整的文档顺序，无需像现在这样用 `chunk_level, parent_chunk_id, id` 三列组合排序。

同时将 `resultIndexToDbId` 从 `HashMap` 改为 `LinkedHashMap` 以消除迭代顺序隐患：

```java
Map<Integer, Integer> resultIndexToDbId = new LinkedHashMap<>();
```

#### 步骤 4：修改 Service 层的 getChunkTree

**文件：** [DocumentServiceImpl.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/service/impl/DocumentServiceImpl.java)

当前 SQL 改用 `sort_order` 后，`getChunkTree()` 方法无需大改。但建议优化 level1 筛选逻辑，确保树构建更健壮：

```java
// 当前代码依赖 chunk_level 判断层级
List<Chunk> level1Chunks = allChunks.stream()
        .filter(c -> c.getParentChunkId() == null)
        .collect(Collectors.toList());
```

此逻辑无需修改（通过 `parent_chunk_id IS NULL` 判断根节点是标准做法）。但需确认：SQL 排序 `ORDER BY sort_order ASC` 已保证 `allChunks` 列表按文档顺序排列，Stream 的顺序性传递保证了 `level1Chunks` 和 children 的顺序。

#### 步骤 5：ChunkTreeVO 添加 sortOrder 字段（可选）

**文件：** [ChunkTreeVO.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/vo/ChunkTreeVO.java)

可在 VO 中添加 `sortOrder` 字段供前端使用（如显示节点编号）：

```java
private Integer sortOrder;
```

---

## 五、实施优先级和依赖关系

```
阶段一（高优先级）：
  └─ 四、分块排序修复
       ├─ DB migration: ALTER TABLE chunks ADD sort_order
       ├─ Chunk entity + Mapper XML 修改
       ├─ ChunkPipelineCoordinator 改造 + HashMap → LinkedHashMap
       └─ 验证：重新导入文档，确认树顺序正确

阶段二（中优先级）：
  └─ 三、导航弹窗化改造
       ├─ ChunkTreeNav.vue 加 overlay + 弹窗容器
       ├─ 键盘事件 + 节点统计 footer
       └─ 移动端适配

阶段三（后续）：
  └─ 二、2.2 节点顺序号（基于 sort_order 在 UI 中展示序号）
  └─ 一、1.1 类型筛选功能
  └─ 二、2.3 状态持久化
```

---

## 六、上下文定位增强 ⏸️ 暂缓执行

> **理由：** 本章节涉及全链路新增 `startPos` 字段（DB → Entity → DTO → Pipeline → Mapper → VO → 前端），且需改造多个 chunk 策略（尤其是使用 `String.split()` 的 StructureAwareChunkStrategy、LineBasedChunkStrategy、RecursiveChunkStrategy），改动范围大、风险高。待阶段一～三稳定完成后再评估执行时机。

### 6.1 原文跳转

**功能描述：** 点击导航树的某个节点后，能将关联的"预览"面板自动滚动到该节点对应原文内容的位置，并在原文中高亮该段落。

**字段分析 —— 全链路需要新增的字段：**

当前系统中**没有任何字段记录分块在原始文档中的位置信息**。要实现原文跳转，需要在以下每一层新增 `startPos`（分块文本在原始文档中的起始字符位置）：

```
数据库 chunks 表
    ↓ 新增列
Chunk entity
    ↓ 新增字段
ChunkResult (策略产出)
    ↓ 新增字段
ChunkPipelineCoordinator (buildChunk桥接)
    ↓ 传递
ChunkMapper.xml (SQL映射)
    ↓ 查询
ChunkTreeVO (API返回)
    ↓ JSON
ChunkTreeNode.vue (前端展示 + 点击事件)
```

**各层具体改造清单：**

| 层级 | 文件 | 改造内容 |
|---|---|---|
| **DB** | `chunks` 表 | `ALTER TABLE chunks ADD COLUMN start_pos integer;` — 记录分块文本在原始文档中的起始字符偏移 |
| **Entity** | [Chunk.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/entity/Chunk.java) | 新增 `private Integer startPos;` |
| **DTO** | [ChunkResult.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/strategy/ChunkResult.java) | 新增 `private Integer startPos;` |
| **Mapper XML** | [ChunkMapper.xml](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/resources/mapper/ChunkMapper.xml) | BaseResultMap 添加 `<result column="start_pos" property="startPos"/>`；BaseColumnList 添加 `start_pos`；INSERT 添加 `#{startPos}` |
| **Pipeline** | [ChunkPipelineCoordinator.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/pipeline/ChunkPipelineCoordinator.java) | `buildChunk()` 中添加 `.startPos(r.getStartPos())` |
| **VO** | [ChunkTreeVO.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/vo/ChunkTreeVO.java) | 新增 `private Integer startPos;` |
| **Service** | [DocumentServiceImpl.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/service/impl/DocumentServiceImpl.java) | `toChunkTreeVO()` 中添加 `.startPos(chunk.getStartPos())` |

**各策略获取 startPos 的方式：**

| 策略 | 获取 startPos 方式 | 难度 |
|---|---|---|
| MarkdownChunkStrategy | `splitByHeadings()` 中 `headingStart` 变量已记录标题位置；文本内容起始 = `headingEnd`；可用 `fullText.indexOf(sectionText)` 或构建时传入 | 低 |
| CodeChunkStrategy | `Match` 类已记录 `start` 位置，直接传入 `ChunkResult` | 极低 |
| SemanticChunkStrategy | LLM 已返回 `{start, end}`，直接映射 | 极低 |
| StructureAwareChunkStrategy | `String.split()` 后可用 `fullText.indexOf(chunkText)` 回查，但需处理重复文本的歧义；更可靠的方式是改造为按 `Matcher.find()` 方式记录偏移 | 中 |
| HtmlChunkStrategy | `HeadingMatch` 已记录 `start` 位置 | 低 |
| LineBasedChunkStrategy | `String.split()` 后需 `indexOf` 回查，存在重复文本歧义 | 中 |
| RecursiveChunkStrategy | LangChain4j `TextSegment` 可能不暴露原始偏移量，需要查 API 或在拆分前自行记录位置 | 中-高 |

> **重要提示：** StructureAwareChunkStrategy、LineBasedChunkStrategy、RecursiveChunkStrategy 使用 `String.split()` 或外部库拆分文本，无法直接获得分块在原文中的偏移。需要改造拆分逻辑：要么换用 `Matcher.find()` 方式记录偏移，要么在遍历时维护一个 `cursor` 变量追踪当前位置（推荐方案，侵入性最小）。

**通用 cursor 方案（推荐用于 split 类策略）：**
```java
int cursor = 0;
for (String section : sections) {
    String trimmed = section.trim();
    if (trimmed.isEmpty()) continue;
    int startPos = fullText.indexOf(trimmed, cursor);  // 从上次结束位置开始查找
    cursor = startPos + trimmed.length();
    // ... 构建 ChunkResult 时传入 startPos
}
```

**前端跳转实现：**
- [ChunkTreeNode.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChunkTreeNode.vue) `@select` 事件传递 `node.startPos`
- [NotesPanel.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/NotesPanel.vue) `handleChunkSelect` 中：若已打开预览面板，调用预览面板的 `scrollToPosition(startPos)` 方法
- [DocumentPreview.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/DocumentPreview.vue) 新增 `scrollToPosition(pos)` 方法，根据预览内容计算滚动偏移

### 6.2 高亮显示

**功能描述：** 在预览面板中高亮当前选中分块对应的文本段落。

**依赖：** 必须先完成 6.1 原文跳转（需要 `startPos` 和文本长度来定位高亮范围）。

**实施要点：**
- 在预览面板中定位到对应段落后，给该段加上高亮样式（`background: #ffeb3b` 或类似）
- 需要在 `ChunkTreeVO` 中额外传递 `chunkText` 的首段内容或完整的 `chunkText` 内容用于匹配
- 如果是 PDF 分页预览，需要定位到具体页码
