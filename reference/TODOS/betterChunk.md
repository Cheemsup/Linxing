******************************本文件内容已全部完成，除非显式指定，否则无需查看**********************************

我考虑一个较为复杂的、应对不同情况的文本chunk方式：
1、采用混合分块策略+Small-to-Big 检索的方式进行chunk（具体而言是先根据md文件明确的格式符做切分，最大程度保留原先笔记的分块，如果某个chunk大小太大，则进一步通过LangChain4j 的 DocumentSplitter 递归字符分割，下层的分割通过Small-to-Big建立与上层分割的联系）。这是普适性广的做法
2、对于特殊的情况，我目前考虑到可以在用户传入文件时就让用户可以指定文件格式（或者系统自动判断），对于有明显格式区分的文件类型（markdown、html、代码文件等）时就可以通过文档结构容易地做高质量地chunk；对于切分时发现的缺乏上下文的块，则通过补充背景信息的方式丰富它的含义
3、对于实在混乱的内容、结构，使用LLM介入的语义分割方式做chunk，LLM阅读源文件，为不同位置打上"分割标记"，系统再依此做chunk。耗时长，作为兜底方案
4、为系统增添关键词匹配或者文件路径匹配的功能，用于快速定位内容位置。也许需要使用到BM25算法

---

# 详细施工流程

> 策略模式负责"选择如何切分"（chunk策略），责任链模式负责"切分后逐项处理"（chunk后处理流水线）。
> 架构原则：面向接口编程，每个策略/处理器独立可测、可插拔、可配置。

---

## Phase 1 — 数据库迁移（oldTables → newTables） 注意！注意！此步骤目前以全部完成，无需做这一步

### 1.1 新增表：无需新建表，全部为 ALTER TABLE + CREATE INDEX

| 表 | 操作 | 说明 |
|---|---|---|
| `users` | 添加 COMMENT ON 语句（表+列） | 仅文档化，无结构变更 |
| `users` | 新增索引 `idx_users_username_btree` | 与已有索引去重（已有 `users_username_key` 唯一索引 + `idx_users_username`），评估必要性后决定是否添加 |
| `documents` | `ALTER TABLE ADD COLUMN chunk_strategy varchar(50) DEFAULT 'auto'` | 记录最终采用的分块策略名称 |
| `documents` | 添加 COMMENT ON 语句 | |
| `chunks` | **核心重构**，需完整重定义表结构 | 见下方 1.2 |
| `embeddings` | 修改主键类型（UUID→SERIAL）、添加 NOT NULL 约束和外键 | 见下方 1.3 |
| `activity_logs` | 添加 COMMENT ON 语句 | 仅文档化 |

### 1.2 chunks 表迁移方案

**因改动巨大（新增7列+2个外键），推荐方案：**

```
1. 备份旧 chunks 数据（pg_dump 或 CREATE TABLE chunks_backup AS SELECT * FROM chunks）
2. DROP TABLE chunks CASCADE（将级联删除 embeddings 中的外键引用）
3. 执行 newTables.md 中 chunks 的完整 DDL
4. 从 chunks_backup 迁移旧数据：
   - chunk_text, user_id, document_id 直接映射
   - page_number 废弃（新表无此字段）
   - 新增列使用默认值：chunk_level=1, chunk_type='general', is_searchable=true
5. 重建 embeddings 表（步骤 1.3）并重新嵌入所有文档
```

**简化方案（适用于数据量小或可重建索引）：**
直接 DROP chunks + embeddings，重新上传所有文档触发新流程。

### 1.3 embeddings 表迁移方案

```
1. DROP TABLE embeddings（向量数据可重建）
2. 执行 newTables.md 中 embeddings 的完整 DDL
```

### 1.4 迁移脚本输出

编写 `src/main/resources/db/migration/V1__upgrade_schema.sql`，内容为完整的迁移 SQL。

---

## Phase 2 — 后端实体层（Entity / Record / VO）改造

### 2.1 `Chunk.java` 实体更新

**文件：** `src/main/java/.../entity/Chunk.java`

| 旧字段 | 新字段/变更 | 类型 |
|---|---|---|
| `id` | 保留 | `Integer` |
| `userId` | 保留 | `Integer` |
| `documentId` | 保留 | `Integer` |
| `chunkText` | 保留 | `String` |
| `pageNumber` | **移除** | — |
| — | `parentChunkId` **新增** | `Integer` |
| — | `chunkLevel` **新增** | `Short` (默认1) |
| — | `chunkType` **新增** | `String` (默认"general") |
| — | `titlePath` **新增** | `String` |
| — | `contextPrefix` **新增** | `String` |
| — | `sourceStrategy` **新增** | `String` |
| — | `needsContext` **新增** | `Boolean` (默认false) |
| — | `isSearchable` **新增** | `Boolean` (默认true) |
| — | `tsContent` **新增** | `String`（tsvector 文本，不直接操作） |
| `createdAt` | 保留 | `OffsetDateTime` |

### 2.2 `FullEmbeddingRecord.java` 更新

| 旧 | 新 |
|---|---|
| `UUID id` | `Integer id`（SERIAL） |
| `Integer userId` | 保留 |
| `Integer documentId` | 保留，改为 `@NotNull` |
| `Integer chunkId` | 保留，改为 `@NotNull` |
| `String embeddingVector` | 保留 |
| `String text` | 保留 |
| `String metadata` | 保留，默认值 `'{}'::jsonb` 对应 Java 端兜底 `"{}"` |

### 2.3 `DocRecord.java` 新增字段

新增 `chunkStrategy`（String, 默认 "auto"）

### 2.4 `VectorSearchResult.java` 更新

适配新的 chunks 表字段（移除 pageNumber，新增 chunkType / titlePath 等查询字段）

---

## Phase 3 — 策略模式：Chunk 分块策略体系

### 3.1 核心接口设计

```
src/main/java/.../strategy/
├── ChunkStrategy.java          # 分块策略接口
├── ChunkStrategyContext.java   # 策略上下文（携带文件信息、用户偏好等）
├── ChunkResult.java            # 分块结果（包含父子关系）
├── ChunkRefinementPipeline.java # 超大块细分链（段落→句子→递归），被所有策略复用
├── impl/
│   ├── MarkdownChunkStrategy.java      # Markdown 结构分块（按 # 标题层级）
│   ├── HtmlChunkStrategy.java          # HTML 结构分块（按 h1~h6 / section / article）
│   ├── CodeChunkStrategy.java          # 代码文件分块（按函数/类/方法边界）
│   ├── StructureAwareChunkStrategy.java# docx/pdf 结构化分块（按标题样式/段落）
│   ├── LineBasedChunkStrategy.java     # 行式文件分块（log/csv/tsv/纯空行分界）
│   ├── RecursiveChunkStrategy.java     # LangChain4j 递归分块（通用兜底）
│   └── SemanticChunkStrategy.java      # LLM 语义分块（仅手动指定或紧急兜底）
└── ChunkStrategyFactory.java  # 策略工厂（含内容探测，不只看扩展名）
```

### 3.2 `ChunkStrategy` 接口定义

```java
public interface ChunkStrategy {
    /**
     * 判断该策略是否适用于当前上下文
     */
    boolean supports(ChunkStrategyContext context);

    /**
     * 执行分块，返回分块结果列表
     * Level 1 为结构化分块（大粒度），Level 2 为递归细分块（小粒度）
     * Level 2 的 parentChunkId 指向对应的 Level 1 chunk
     */
    List<ChunkResult> execute(ChunkStrategyContext context);
}
```

### 3.3 `ChunkStrategyContext` 上下文

```java
public class ChunkStrategyContext {
    String fileType;           // pdf / docx / md / html / txt / java ...
    String fileName;
    String fullText;           // 解析后的完整文本
    org.document4j.Document document;  // LangChain4j Document 对象
    Integer maxChunkSize;      // Level 1 的最大 chunk 大小（超过则触发递归细分）
    Integer chunkOverlap;      // 递归细分时的重叠量
    Map<String, Object> extra; // 扩展参数
}
```

### 3.4 `ChunkResult` 数据结构

```java
public class ChunkResult {
    Integer parentChunkId;      // 父 chunk ID（Level 2 -> Level 1），null 表示 Level 1
    Short chunkLevel;           // 1=大粒度, 2=小粒度(可检索)
    String chunkText;
    String titlePath;           // 从结构化分块中提取
    String chunkType;           // general / section / code / table / qa_pair
    String sourceStrategy;      // 本 chunk 的生成策略名
    Map<String, Object> metadata; // 附加元数据
}
```

### 3.5 `ChunkStrategyFactory` 策略工厂

策略选择优先级（从高到低），**不只看扩展名，同时做轻量内容探测**：

| 优先级 | 条件 | 策略 | 说明 |
|---|---|---|---|
| P1 | 用户显式指定 `chunkStrategy` | 按名称反射获取 | 用户说了算 |
| P2 | 扩展名=`md` OR 内容前200字检出 `#`/`##`/` ``` ` 等 Markdown 特征 | `MarkdownChunkStrategy` | 容忍 `.txt` 含 md 语法的笔记 |
| P3 | 扩展名=`html`/`htm` | `HtmlChunkStrategy` | |
| P4 | 扩展名∈`{java,py,js,ts,go,rs,c,cpp,cs,kt,...}` OR 内容有 `package`/`import`/`class`/`def` 等代码特征 | `CodeChunkStrategy` | |
| P5 | 扩展名=`docx`/`pdf` 且解析出清晰结构（标题样式/段落层级） | `StructureAwareChunkStrategy` | |
| P6 | 扩展名∈`{log,csv,tsv}` OR 内容检测到行式结构（>80% 的非空行长度接近） OR 内容主要由空行分隔的段落组成 | `LineBasedChunkStrategy` | **新增**，见 3.7 节 |
| P7 | 以上均不匹配（无结构的长文本、混乱内容） | `RecursiveChunkStrategy` | **通用兜底**，不限文本长度 |
| P8 | 用户显式指定 `semantic`，或 P2~P7 中任一策略执行后返回空结果 | `SemanticChunkStrategy` | **不再自动触发**，避免昂贵的无意义 LLM 调用 |

### 3.6 各策略详细说明

#### MarkdownChunkStrategy
- **分块逻辑**：按 `#` 标题层级切分（`#` → `##` → `###` ...），每个标题及其下属内容为一个 chunk
- **titlePath**：从标题层级拼接，如 "项目A > 设计文档 > 接口定义"
- **超大块处理**：若 chunk 文本长度 > `maxChunkSize`，触发 `RecursiveChunkStrategy` 细分
  - 生成 Level 1 父块（chunkLevel=1, isSearchable=false）
  - 生成若干 Level 2 子块（chunkLevel=2, isSearchable=true, parentChunkId=父块ID）
- **chunkType**：`section`（标题段落）、`code`（代码块）、`table`（表格）

#### HtmlChunkStrategy
- **分块逻辑**：按 `<h1>~<h6>` / `<section>` / `<article>` 标签分块
- **titlePath**：从标题标签提取
- 其余与 MarkdownChunkStrategy 类似

#### CodeChunkStrategy
- **分块逻辑**：按函数/类/方法边界分块（基于 AST 或正则匹配）
- **chunkType**：`code`
- **titlePath**：类名 > 方法名

#### StructureAwareChunkStrategy
- **分块逻辑**：对 docx 按段落/标题样式分块；对 PDF 按页+段落分块
- 使用 Apache POI / PDFBox 的结构信息

#### RecursiveChunkStrategy
- **分块逻辑**：使用 `DocumentSplitters.recursive(chunkSize, chunkOverlap)`
- 所有 chunk 为 Level 2（isSearchable=true）
- 作为通用兜底策略（不限文本长度，任何 P2~P6 不匹配的输入走此策略）

#### LineBasedChunkStrategy ← 新增
- **动机**：笔记中常见的"只用空行分隔内容块"模式，以及日志/CSV 等行式数据
- **触发条件**：
  - 文件扩展名为 `log` / `csv` / `tsv`，或
  - 内容探测：统计非空行长度分布，若方差较小（>80% 行长度接近）→ 判定为行式数据，或
  - 内容中连续 2+ 个空行频繁出现（>3 处）→ 判定为空行分界的段落文本
- **分块逻辑**：
  - 以至少一个空行（`\n\s*\n`）作为分割边界
  - 每段为一个 Level 2 chunk
  - 若一个段落仍超过 `maxChunkSize` → 触发 `ChunkRefinementPipeline` 进一步细分（见 3.8）
- **chunkType**：`general`（段落），若段落以 `#` 开头则标记为 `section`

#### SemanticChunkStrategy ← 定位降级
- **分块逻辑**：将全文以分段形式提交给 LLM，LLM 返回分割位置索引
- **提示词模板**：
  ```
  你是一个文本分块助手。请阅读以下文本，标记出语义上应该分开的边界位置。
  返回格式：JSON 数组，每项为 {start: 起始字符索引, end: 结束字符索引, summary: 该段摘要}
  ```
- **触发条件**：仅用户显式指定 `chunkStrategy=semantic`，或 P2~P7 全部执行后返回空结果时作为紧急兜底
- **安全阀**：若文本长度 > `semanticMaxLength`（可配置，默认 10000），拒绝执行并降级为 `RecursiveChunkStrategy`，避免 token 爆表

### 3.7 `ChunkRefinementPipeline` — 超大块细分回退链（核心机制）

**动机**：解决笔记中"只有大标题，没有子标题，内容用空行分界"的场景。

**问题场景还原**：
```
文件：mysql-notes.md
内容：
# MySQL基础
mysql的几种join方式...
空行
索引的创建方式...
空行
查询优化的explain用法...

# 事务
事务的ACID特性...
空行
MVCC多版本并发控制的工作原理...
空行
幻读产生的原因及解决方案...
```

MarkdownChunkStrategy 按 `#` 切分为 2 个 Level 1 chunk：`"MySQL基础"` 和 `"事务"`，每个都可能超过 800 字。此时**不是直接交给 RecursiveChunkStrategy 暴力切字**，而是走 `ChunkRefinementPipeline` 分层尝试：

```
ChunkRefinementPipeline 内部回退优先级：

Step 1: 空行分界（ParagraphSplitter）← 优先尝试
    ↓ 若某段落仍 > maxChunkSize
Step 2: 句子分界（SentenceSplitter）
    ↓ 若某句子仍 > maxChunkSize
Step 3: 递归字符分割（RecursiveChunkStrategy）← 最终兜底
```

**具体流程**：
1. `ParagraphSplitter`：按 `\n{2,}` 正则拆分，尊重用户手动加入的空行边界
   - 每个段落成为 Level 2 chunk（parentChunkId = Level1 chunk ID）
   - 若段落 ≤ maxChunkSize → 完成
   - 若段落 > maxChunkSize → 对该段落继续 Step 2
2. `SentenceSplitter`：按中英文句号/感叹号/问号 + 换行分割
   - 每个句子成为 Level 2 chunk
   - 若句子 > maxChunkSize → 对该句子继续 Step 3
3. `RecursiveChunkStrategy`：LangChain4j 字符级递归分割
   - 最终无论如何都能切出合适大小的 chunk

**效果**：`"MySQL基础"` 下的每个自然段落（join 方式 / 索引创建 / explain 用法）成为独立的可检索 chunk，语义完整。

**文件位置**：`src/main/java/.../strategy/ChunkRefinementPipeline.java`
- 被 `MarkdownChunkStrategy`、`HtmlChunkStrategy`、`StructureAwareChunkStrategy`、`LineBasedChunkStrategy` 等方法内复用
- 不是独立策略，无法被工厂选中；是策略内部的细分工具

---

## Phase 4 — 责任链模式：Chunk 后处理流水线

### 4.1 核心接口设计

```
src/main/java/.../pipeline/
├── ChunkProcessingHandler.java       # 处理器接口
├── ChunkProcessingContext.java       # 处理上下文（携带 chunk、文档信息）
├── ChunkProcessingPipeline.java      # 流水线编排器
└── handler/
    ├── ChunkTypeClassifier.java      # 分类 chunk_type
    ├── TitlePathExtractor.java        # 提取 title_path
    ├── ContextEnricher.java           # 弱上下文块补充 context_prefix
    ├── FullTextIndexer.java           # 生成 ts_content
    ├── SearchabilityMarker.java       # 标记 is_searchable
    └── EmbeddingPersistHandler.java   # 嵌入生成 + 持久化
```

### 4.2 `ChunkProcessingHandler` 接口

```java
public interface ChunkProcessingHandler {
    /**
     * 处理当前 chunk，返回是否继续传递给下一个处理器
     * @return true = 继续传递, false = 终止链
     */
    boolean handle(ChunkProcessingContext context);

    /** 处理器优先级，数字越小越先执行 */
    int order();
}
```

### 4.3 `ChunkProcessingContext` 上下文

```java
public class ChunkProcessingContext {
    Chunk chunk;                     // 当前处理的 Chunk 实体
    DocRecord document;              // 所属文档
    String fullDocumentText;         // 文档全文（用于上下文补充）
    Map<String, Object> attributes;  // 链上传递的额外数据
    boolean shouldPersist;           // 是否需要持久化到数据库
}
```

### 4.4 处理器执行顺序与职责

| 序号 | Handler | 职责 | 触发条件 |
|---|---|---|---|
| 0 | `ChunkTypeClassifier` | 根据文本特征设置 chunkType：检测代码块(```)/表格(|)/QA对(Q: A:)/列表等 | 总是执行 |
| 1 | `TitlePathExtractor` | 从 markdown 标题或 HTML 标签中提取并设置 titlePath | 存在标题结构时 |
| 2 | `ContextEnricher` | 对 needsContext=true 的弱上下文块，调用 LLM 生成 contextPrefix | needsContext=true |
| 3 | `FullTextIndexer` | 调用 `to_tsvector('simple', chunkText)` 生成 tsContent 值，存入 DB | 总是执行 |
| 4 | `SearchabilityMarker` | 根据 chunkLevel 设置 isSearchable（仅 Level 2 参与检索） | 总是执行 |
| 5 | `EmbeddingPersistHandler` | 调用 EmbeddingModel 生成向量，插入 embeddings 表 | isSearchable=true 时 |

### 4.5 `ChunkProcessingPipeline` 流水线管理器

```java
@Component
public class ChunkProcessingPipeline {
    private final List<ChunkProcessingHandler> handlers;

    // Spring 自动注入所有 ChunkProcessingHandler 实现，按 order() 排序
    public ChunkProcessingPipeline(List<ChunkProcessingHandler> handlers) {
        this.handlers = handlers.stream()
            .sorted(Comparator.comparingInt(ChunkProcessingHandler::order))
            .toList();
    }

    public void execute(ChunkProcessingContext context) {
        for (ChunkProcessingHandler handler : handlers) {
            if (!handler.handle(context)) break;
        }
    }
}
```

### 4.6 核心逻辑说明

#### ChunkTypeClassifier 分类规则

| 检测特征 | chunkType |
|---|---|
| 包含 ` ``` ` 代码块标记 | `code` |
| 包含 `| ... |` 表格行 | `table` |
| 包含 `Q:` / `A:` / `问：` / `答：` 模式 | `qa_pair` |
| 以 `#` 标题行开头 | `section` |
| 文本较短且无明确上下文关键词 | `context_weak` |
| 以上均不匹配 | `general` |

#### ContextEnricher（弱上下文补充）

仅当 `chunkType = context_weak` 或 `needsContext = true` 时执行：
1. 拼接 prompt：`以下文本片段来自文档"{文档标题}"，请用1-2句话描述它的背景和主题：\n{chunkText}`
2. 调用 LLM（轻量模型）生成背景描述
3. 将结果写入 `chunk.contextPrefix`
4. 更新 `chunk.chunkType = "context_weak"`，`chunk.needsContext = false`

#### EmbeddingPersistHandler

对 `isSearchable = true` 的 chunk：
1. **文本预处理**：`(contextPrefix ?: "") + " " + (titlePath ?: "") + " " + chunkText`
2. 调用 `EmbeddingModel.embed(text)` 生成 512 维向量
3. 构建 `FullEmbeddingRecord` 并批量插入 `embeddings` 表
4. metadata JSON 包含：`{user_id, document_id, chunk_id, chunk_type, parent_chunk_id, title_path, strategy}`

---

## Phase 5 — 后端 Service 层改造

### 5.1 `EmbeddingHelper` → 重构为 `ChunkPipelineService`

**文件：** `src/main/java/.../service/ChunkPipelineService.java`

```java
@Service
public class ChunkPipelineService {
    // 注入
    ChunkStrategyFactory strategyFactory;
    ChunkProcessingPipeline pipeline;
    ChunkMapper chunkMapper;
    EmbeddingMapper embeddingMapper;
    DocumentMapper documentMapper;
    EmbeddingModel embeddingModel;

    /**
     * 完整的分块+处理流程
     */
    @Transactional
    public int processDocument(DocRecord doc, String fullText, Document langChainDoc) {
        // 1. 构建策略上下文
        ChunkStrategyContext ctx = ChunkStrategyContext.builder()
            .fileType(doc.getFileType())
            .fileName(doc.getFileName())
            .fullText(fullText)
            .document(langChainDoc)
            .maxChunkSize(ragProperties.getEmbedding().getChunkSize())
            .chunkOverlap(ragProperties.getEmbedding().getChunkOverlap())
            .build();

        // 2. 选择并执行分块策略
        ChunkStrategy strategy = strategyFactory.getStrategy(ctx);
        List<ChunkResult> results = strategy.execute(ctx);

        // 3. 先持久化 Level 1 的 chunk（获得 ID）
        List<Chunk> level1Chunks = persistLevel1(results, doc);

        // 4. 再持久化 Level 2 的 chunk（赋予 parentChunkId）
        List<Chunk> level2Chunks = persistLevel2(results, doc, level1Chunks);

        // 5. 责任链后处理所有 chunk
        for (Chunk chunk : allChunks) {
            ChunkProcessingContext pCtx = new ChunkProcessingContext(chunk, doc, fullText);
            pipeline.execute(pCtx);
        }

        // 6. 更新文档状态
        doc.setStatus("completed");
        doc.setChunkStrategy(strategy.getClass().getSimpleName());
        documentMapper.updateById(doc);

        return allChunks.size();
    }
}
```

### 5.2 `IngestServiceImpl` 改造

| 改动点 | 说明 |
|---|---|
| 新增参数 `chunkStrategy`（可选） | 用户上传时可指定策略名，传入 `ChunkStrategyContext` |
| 替换 `EmbeddingHelper.embedDocument()` | 改为调用 `ChunkPipelineService.processDocument()` |
| 异常处理 | 策略/流水线任意步骤失败时，设文档状态为 `"failed"`，记录错误日志 |
| 异步支持（可选优化） | 大文件分块可异步执行，状态返回 `"processing"`，完成后回调更新 |

### 5.3 `DocumentServiceImpl` 改造

| 改动点 | 说明 |
|---|---|
| `deleteDocument()` | 适配新外键约束：先删 embeddings → 再删 chunks（注意级联关系，子 chunk 先删）→ 再删 document |
| 新增 `getChunksByDocumentId()` | 返回文档下所有 chunk（含 titlePath、chunkType、parentChunkId 等） |
| 新增 `getChunkDetail()` | 返回单个 chunk 的完整信息 |

### 5.4 Mapper 层（MyBatis XML）更新

| Mapper | 改动内容 |
|---|---|
| `ChunkMapper.xml` | 所有 INSERT/UPDATE/SELECT 适配新字段；新增 `findByParentId`、`findByLevel`、`findSearchable`、`updateTsContent` |
| `EmbeddingMapper.xml` | 批量 INSERT 改为 SERIAL 主键（无需 UUID）；查询 JOIN 适配新 chunks 字段 |
| `DocumentMapper.xml` | INSERT/UPDATE 新增 `chunk_strategy` 字段 |

---

## Phase 6 — 树状导航 + 上下文定位

> 核心价值：让用户从"搜索结果"或"笔记列表"直接跳转到"笔记原文的上下文位置"。
> 不暴露 chunk 技术细节（chunkType/chunkLevel/向量等），只做导航和定位。

### 6.1 数据结构：chunk 树

一个文档的 chunk 树结构示例：

```
文档根节点（虚拟节点，不存DB）
├── Level 1 chunk: "MySQL基础" (title_path="MySQL基础", chunk_type=section)
│   ├── Level 2 chunk: "join方式..." (parent_chunk_id=↑, is_searchable=true)
│   ├── Level 2 chunk: "索引创建..." (parent_chunk_id=↑, is_searchable=true)
│   └── Level 2 chunk: "explain用法..." (parent_chunk_id=↑, is_searchable=true)
├── Level 1 chunk: "事务" (title_path="事务", chunk_type=section)
│   ├── Level 2 chunk: "ACID特性..." (parent_chunk_id=↑)
│   └── Level 2 chunk: "MVCC原理..." (parent_chunk_id=↑)
```

### 6.2 新增 API（仅 2 个接口）

#### 接口 1：获取文档的 chunk 树

```
GET /documents/{id}/chunk-tree
```

返回结构：

```json
{
  "documentId": 1,
  "fileName": "mysql-notes.md",
  "children": [
    {
      "chunkId": 10,
      "titlePath": "MySQL基础",
      "chunkLevel": 1,
      "chunkType": "section",
      "textPreview": "MySQL基础\nmysql的几种join方式...",
      "children": [
        {
          "chunkId": 11,
          "titlePath": "MySQL基础",
          "chunkLevel": 2,
          "chunkType": "general",
          "textPreview": "mysql的几种join方式...",
          "children": []
        }
      ]
    }
  ]
}
```

#### 接口 2：获取单个 chunk 的上下文（用于搜索结果跳转）

```
GET /chunks/{id}/context
```

返回结构：

```json
{
  "chunkId": 11,
  "chunkText": "mysql的几种join方式...",
  "parentChunk": {
    "chunkId": 10,
    "titlePath": "MySQL基础",
    "chunkText": "完整的大块文本..."
  },
  "siblingChunks": [
    { "chunkId": 11, "textPreview": "join方式..." },
    { "chunkId": 12, "textPreview": "索引创建..." },
    { "chunkId": 13, "textPreview": "explain用法..." }
  ],
  "documentId": 1,
  "fileName": "mysql-notes.md"
}
```

### 6.3 改造已有 API

| 路径 | 改动 |
|---|---|
| `GET /rag/chat` | 返回结果中 `sources` 新增 `chunkId` 字段，前端点击来源时用于调取上下文 |
| `GET /documents` | 列表返回新增 `chunkStrategy` 字段（系统自动采用的策略记录，只读） |

### 6.4 VO 设计

| VO | 说明 |
|---|---|
| `ChunkTreeVO` | 树形节点，含 children 列表 |
| `ChunkContextVO` | 上下文信息，含 parentChunk + siblingChunks |

### 6.5 前端交互设计

| 场景 | 交互 |
|---|---|
| 笔记管理页 | 点击文档卡片 → 展开侧边栏树形导航 → 点击节点查看内容 |
| 聊天页 | AI 回答中的来源标签可点击 → 弹出该 chunk 的上下文面板（显示父块全文 + 高亮当前 chunk + 兄弟块列表） |

### 6.6 施工步骤

| 步骤 | 内容 | 涉及文件 |
|---|---|---|
| Step 1 | 新增 `ChunkTreeVO` 返回结构 | VO 层 |
| Step 2 | 新增 `ChunkContextVO` 返回结构 | VO 层 |
| Step 3 | ChunkMapper 新增 `findParentAndSiblings` 查询 | Mapper XML |
| Step 4 | DocumentController 新增 `/documents/{id}/chunk-tree` | Controller |
| Step 5 | 新增 `ChunkController` + `/chunks/{id}/context` | Controller |
| Step 6 | 前端 `NotesPanel` 增加树形导航组件 | Vue |
| Step 7 | 前端 `ChatPanel` 来源标签可点击，弹出上下文面板 | Vue |

---

## Phase 7 — BM25 全文检索 + 混合检索

> 核心价值：向量检索擅长语义匹配，BM25 擅长精确关键词匹配。两者结合能覆盖更多搜索场景。

### 7.1 现状分析

当前系统只有纯向量检索：
```
用户提问 → embedding → cosine similarity → topK → rerank → LLM
```

问题场景：
- 用户问"MySQL的join方式" → 向量检索能找到语义相近的 chunk ✅
- 用户问"笔记中提到 MVCC 的地方" → 精确关键词匹配，向量可能找不到 ❌
- 用户问"文件名包含 mysql 的笔记" → 文件名精确匹配，向量完全无能为力 ❌

### 7.2 BM25 全文检索实施

#### 基础设施状态

chunks 表已有 `ts_content tsvector` 字段和 GIN 索引 `idx_chunks_ts_content`，`ChunkMapper.xml` 的 update 语句也已用 `to_tsvector('simple', ...)` 写入。基础设施已就绪，只差查询。

#### BM25 查询 SQL

```sql
SELECT c.id,
       c.chunk_text,
       c.title_path,
       c.chunk_type,
       c.document_id,
       d.file_name,
       ts_rank(c.ts_content, query) AS bm25_score
FROM chunks c,
     to_tsquery('simple', :keywords) query
LEFT JOIN documents d ON c.document_id = d.id
WHERE c.user_id = :userId
  AND c.is_searchable = true
  AND c.ts_content @@ query
ORDER BY bm25_score DESC
LIMIT :limit
```

#### 中文分词方案

| 方案 | 做法 | 效果 | 成本 |
|---|---|---|---|
| A. 保持 `simple` | 中文逐字匹配，英文按空格分词 | 中文只能精确匹配整词，"MVCC"能找到，"并发控制"可能找不到 | 零成本 |
| B. 引入 `zhparser` | PostgreSQL 安装中文分词扩展 | 中文分词效果好 | 需要装扩展 |

**决策**：先用方案 A 上线。笔记系统中用户搜索的大多是专业术语/缩写/英文关键词（如 "MVCC"、"B+树"、"JOIN"），`simple` 配置对这些已经够用。后续发现中文长词搜索效果差再引入 `zhparser`。

#### 关键词提取

```
src/main/java/.../utils/
└── KeywordExtractor.java
```

职责：从自然语言问题中提取关键词，转为 `tsquery` 格式。

```
输入: "MySQL中MVCC是怎么工作的"
输出: "MySQL & MVCC & 工作"
```

规则：
1. 按空格/标点符号分词
2. 过滤停用词（的、了、是、在、有、什么、怎么、how、why 等）
3. 过滤长度 < 2 的碎片词
4. 用 `&` 连接（AND 逻辑），返回 tsquery 格式

### 7.3 混合检索架构

```
用户提问
    │
    ├──→ 向量检索（现有）: embedding → cosine similarity → top N
    │
    ├──→ BM25 关键词检索（新增）: 提取关键词 → tsquery → ts_rank → top N
    │
    └──→ 合并 + 加权打分 → 统一排序 → rerank → topK → LLM
```

#### 融合策略：Reciprocal Rank Fusion (RRF)

RRF 是最简单且效果稳定的混合排序方法，不需要分数归一化：

```
src/main/java/.../utils/
└── ReciprocalRankFusion.java
```

```java
// RRF 公式: score = weight / (K + rank + 1)
// K = 60（经验值），rank 从 0 开始
// 向量权重 0.7，BM25 权重 0.3
```

**为什么选 RRF 而不是线性加权**：向量分数范围 [0,1]，BM25 分数范围可能 [0, 0.01] 或 [0, 100]，量纲不一致，线性加权需要归一化。RRF 基于排名位置，天然归一化。

### 7.4 ChatServiceImpl 改造要点

```
改造流程:
1. 向量检索（现有逻辑不变）
2. BM25 关键词检索（新增，仅当关键词非空且混合检索启用时）
3. RRF 融合 → 得到统一排序的候选列表
4. 转回 VectorSearchResult 格式，走现有的 rerank 流程
5. 后续 rerank + LLM 不变
```

### 7.5 配置扩展

`RagProperties.Search` 新增：

```java
private boolean hybridEnabled = true;
private double vectorWeight = 0.7;
private double bm25Weight = 0.3;
private int bm25RecallSize = 20;
```

### 7.6 施工步骤

| 步骤 | 内容 | 涉及文件 |
|---|---|---|
| Step 1 | 新增 `KeywordExtractor` 工具类 | utils/ |
| Step 2 | 新增 `Bm25SearchResult` record | entity/ |
| Step 3 | ChunkMapper 新增 `bm25Search` 方法 + XML | mapper/ |
| Step 4 | 新增 `ReciprocalRankFusion` 工具类 | utils/ |
| Step 5 | RagProperties.Search 新增混合检索配置 | config/ |
| Step 6 | 改造 `ChatServiceImpl.chat()` 接入混合检索 | service/ |
| Step 7 | 验证：对比纯向量 vs 混合检索的召回效果 | 测试 |

---

## Phase 8 — 清理与精简

### 8.1 移除"用户选择 chunk 策略"

终端用户不需要也不应该参与 RAG 分块策略的选择。系统已通过 `ChunkStrategyFactory` 的自动探测（文件扩展名 + 内容探测）覆盖了所有常见场景。

| 改动点 | 内容 |
|---|---|
| `ChunkStrategyFactory` | 移除 P1 优先级（"用户显式指定"），工厂只做自动探测 |
| `IngestController` | 上传接口移除 `chunkStrategy` 参数 |
| `documents` 表 | `chunk_strategy` 字段保留，但语义变为"系统自动采用的策略记录"（只读） |
| `DocRecord` | 同上，`chunkStrategy` 变为只读记录字段 |
| `ChunkStrategyContext` | 移除用户偏好相关字段（如有） |

### 8.2 砍掉无用的 chunk 管理 API

以下 API 不再需要：
- `GET /documents/{id}/chunks` — 查看 chunk 列表（含类型/level/向量）→ 对用户无意义
- `GET /chunks/{id}` — 查看单个 chunk 详情 → 对用户无意义

保留的只有 Phase 6 中定义的两个面向用户的接口：`chunk-tree` 和 `chunk-context`。

### 8.3 文档精简

TODO.md 中涉及"用户选择策略"、"chunk 管理 API"的内容全部移除。

---

## Phase 9 — 施工顺序建议

```
Phase 1  数据库迁移             ← 已完成
Phase 2  实体层改造             ← 已完成
Phase 3  策略模式               ← 已完成
Phase 4  责任链模式             ← 已完成
Phase 5  Service 层重构         ← 已完成
─────────────────────────────────────────────
Phase 6  树状导航 + 上下文定位  ← 下一步：2个API + 前端树组件 + 聊天来源跳转
Phase 7  BM25 + 混合检索       ← 下一步：KeywordExtractor + BM25 SQL + RRF + ChatService改造
Phase 8  清理精简              ← 移除用户选择策略、无用API、精简文档
```

### 建议优先级

1. **Phase 7（混合检索）**：直接提升问答质量，用户感知最强
2. **Phase 6（树状导航）**：完善笔记浏览体验，增强"笔记系统"的定位感
3. **Phase 8（清理）**：收尾工作，保持代码库整洁

---

## 附录

### A. 关键依赖确认

`pom.xml` 中已有的 LangChain4j 依赖可复用：
- `langchain4j` — `DocumentSplitter`
- `langchain4j-embeddings-bge-small-zh` — 嵌入模型
- `langchain4j-document-parser-apache-poi` — Office 解析
- `langchain4j-open-ai` — LLM 调用（用于上下文补充）
- `langchain4j-pgvector` — pgvector 集成

无需新增依赖核心库。

### B. 中文分词后续优化路径

若 Phase 7 上线后发现中文长词搜索效果不理想：

1. 安装 `zhparser` PostgreSQL 扩展
2. 创建自定义中文分词配置：`CREATE TEXT SEARCH CONFIGURATION chinese_zh (...)`
3. 将 `to_tsquery('simple', ...)` 改为 `to_tsquery('chinese_zh', ...)`
4. 重建 `ts_content` 列