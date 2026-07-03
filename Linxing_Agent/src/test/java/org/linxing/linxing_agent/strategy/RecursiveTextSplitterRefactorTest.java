package org.linxing.linxing_agent.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.linxing.linxing_agent.rag.strategy.impl.MarkdownChunkStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarkdownChunkStrategy 重构后验证测试（v3.1）
 *
 * 核心变化：
 * - 不再使用 RecursiveTextSplitter，改为按句子拆分（splitBySentenceWithThreshold）
 * - 不再有 overlap 机制，句子是原子单位，累加到阈值后输出
 * - chunkThreshold = 1000（配置化）
 * - titlePath 最多三层（一二三级标题）
 * - 超长标题区块：Level1 父chunk + Level2 子chunk（父子层级）
 * - 无标题文档：三级降级拆分（强段落 → 弱段落 → 句子）
 *
 * 验证点：
 * - 第一次改造 Phase 1：标题识别简化（一二三级，titlePath最多三层）
 * - 第一次改造 Phase 2：超长标题区块按句子拆分（无overlap，句子完整性）
 * - 第一次改造 Phase 3：无标题文档处理（段落划分+阈值累加）
 * - 第一次改造 Phase 4：chunkLevel父子层级（超长区块有父子，短区块无父子）
 * - 第二次改造 Phase 1：跳过空内容标题区块
 * - 第二次改造 Phase 2：无标题文档三级降级拆分（强段落→弱段落→句子）
 */
@DisplayName("MarkdownChunkStrategy 重构后验证测试（v3.1）")
class RecursiveTextSplitterRefactorTest {

    private static final String TEST_FILES_DIR_ROOT = "reference/TODOS/betterRAG/testFiles";
    private static final String TEST_FILES_DIR_MODULE = "../reference/TODOS/betterRAG/testFiles";

    private String longParagraphContent;
    private String shortSectionsContent;
    private String embeddedContent;

    @BeforeEach
    void setUp() throws IOException {
        longParagraphContent = loadTestFile("long_paragraph.md");
        shortSectionsContent = loadTestFile("short_sections.md");
        embeddedContent = loadTestFile("嵌入式.md");
    }

    private String loadTestFile(String fileName) throws IOException {
        Path path1 = Paths.get(TEST_FILES_DIR_ROOT, fileName);
        Path path2 = Paths.get(TEST_FILES_DIR_MODULE, fileName);
        Path path = Files.exists(path1) ? path1 : path2;
        if (!Files.exists(path)) {
            throw new IOException("Test file not found: " + fileName);
        }
        return Files.readString(path);
    }

    // ==================== 第一次改造 Phase 1：标题识别简化验证 ====================

    @Nested
    @DisplayName("第一次改造 Phase 1: 标题识别简化验证")
    class Phase1HeadingRecognitionTest {

        @Test
        @DisplayName("P1-1: titlePath 最多三层")
        void testTitlePathMaxThreeLevels() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fileName("short_sections.md")
                    .fullText(shortSectionsContent)
                    .chunkThreshold(1000)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== P1-1: titlePath 最多三层 ===");
            for (ChunkResult r : results) {
                if (r.getTitlePath() != null) {
                    int levelCount = r.getTitlePath().split(" > ").length;
                    System.out.printf("titlePath='%s', 层级=%d%n", r.getTitlePath(), levelCount);
                    assertTrue(levelCount <= 3, "titlePath 层级不应超过3层");
                }
            }
        }

        @Test
        @DisplayName("P1-2: 短标题区块直接作为 Level2")
        void testShortSectionAsLevel2() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(shortSectionsContent)
                    .chunkThreshold(1000)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== P1-2: 短标题区块作为 Level2 ===");
            long level1Count = results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count();
            System.out.println("Level1数: " + level1Count);
            assertEquals(0, level1Count, "短文档不应产生 Level1");
        }
    }

    // ==================== 第一次改造 Phase 2：超长标题区块按句子拆分验证 ====================

    @Nested
    @DisplayName("第一次改造 Phase 2: 超长标题区块按句子拆分")
    class Phase2SentenceSplitTest {

        @Test
        @DisplayName("P2-1: 超长标题区块创建父子层级")
        void testLongSectionCreatesParentChild() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(longParagraphContent)
                    .chunkThreshold(500)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== P2-1: 超长区块父子层级 ===");
            long level1Count = results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count();
            System.out.println("Level1数: " + level1Count);
            assertTrue(level1Count > 0, "超长文档应产生 Level1");
        }
    }

    // ==================== 第一次改造 Phase 3：无标题文档处理验证 ====================

    @Nested
    @DisplayName("第一次改造 Phase 3: 无标题文档处理")
    class Phase3NoTitleDocumentTest {

        @Test
        @DisplayName("P3-1: 无标题区块按段落划分")
        void testNoTitleSectionParagraphSplit() {
            String noTitleText = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(noTitleText)
                    .chunkThreshold(100)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== P3-1: 无标题文档段落划分 ===");
            for (ChunkResult r : results) {
                System.out.printf("chunk: '%s', titlePath=%s%n",
                        r.getChunkText().substring(0, Math.min(20, r.getChunkText().length())),
                        r.getTitlePath());
                assertNull(r.getTitlePath(), "无标题文档 titlePath 应为 null");
            }
        }
    }

    // ==================== 第一次改造 Phase 4：父子层级验证 ====================

    @Nested
    @DisplayName("第一次改造 Phase 4: 父子层级验证")
    class Phase4ParentChildTest {

        @Test
        @DisplayName("P4-1: 短标题区块无父子层级")
        void testShortSectionNoParentChild() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(shortSectionsContent)
                    .chunkThreshold(1000)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            long withParent = results.stream().filter(r -> r.getParentChunkId() != null).count();
            System.out.println("=== P4-1: 短标题区块无父子 ===");
            System.out.println("有父块的chunk数: " + withParent);
            assertEquals(0, withParent, "短标题区块不应有父块");
        }

        @Test
        @DisplayName("P4-2: 超长标题区块有父子层级")
        void testLongSectionHasParentChild() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(longParagraphContent)
                    .chunkThreshold(500)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            long withParent = results.stream()
                    .filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_2)
                    .filter(r -> r.getParentChunkId() != null)
                    .count();
            System.out.println("=== P4-2: 超长标题区块有父子 ===");
            System.out.println("有父块的Level2数: " + withParent);
            assertTrue(withParent > 0, "超长标题区块应有父子层级");
        }
    }

    // ==================== 第二次改造 Phase 1：跳过空内容标题区块 ====================

    @Nested
    @DisplayName("第二次改造 Phase 1: 跳过空内容标题区块")
    class SecondPhase1EmptyHeadingTest {

        @Test
        @DisplayName("SP1-1: 空标题区块不生成独立chunk")
        void testEmptyHeadingNotGenerateChunk() {
            // 模拟计划文档中的问题场景：一级标题后紧跟二级标题（无实质内容）
            String emptyHeadingText = """
                # 核心算法详解
                ## 分块策略设计
                本段内容用于测试空标题区块跳过功能。

                ## 另一个二级标题
                更多内容...
                """;

            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(emptyHeadingText)
                    .chunkThreshold(1000)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== SP1-1: 空标题区块不生成独立chunk ===");
            for (ChunkResult r : results) {
                System.out.printf("chunkLevel=%d, titlePath='%s', chunkText长度=%d%n",
                        r.getChunkLevel(), r.getTitlePath(), r.getChunkText().length());
                // "核心算法详解" 不应单独作为一个 chunk
                assertFalse(r.getChunkText().trim().equals("# 核心算法详解"),
                        "空标题区块不应单独作为chunk");
            }

            // 验证 titlePath 仍正确包含父标题路径
            ChunkResult secondLevelChunk = results.stream()
                    .filter(r -> r.getTitlePath() != null && r.getTitlePath().contains("分块策略设计"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(secondLevelChunk, "应存在分块策略设计的chunk");
            assertTrue(secondLevelChunk.getTitlePath().contains("核心算法详解"),
                    "子标题的titlePath应包含父标题路径");
        }

        @Test
        @DisplayName("SP1-2: titlePath仍正确记录层级路径")
        void testTitlePathStillCorrect() {
            String nestedHeadingText = """
                # 一级标题
                ## 二级标题
                ### 三级标题
                这是三级标题下的内容。
                """;

            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(nestedHeadingText)
                    .chunkThreshold(1000)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== SP1-2: titlePath仍正确记录层级路径 ===");
            for (ChunkResult r : results) {
                System.out.printf("titlePath='%s'%n", r.getTitlePath());
            }

            // 找到三级标题的chunk
            ChunkResult level3Chunk = results.stream()
                    .filter(r -> r.getTitlePath() != null && r.getTitlePath().contains("三级标题"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(level3Chunk, "应存在三级标题的chunk");
            // titlePath应包含完整路径
            assertTrue(level3Chunk.getTitlePath().contains("一级标题"), "titlePath应包含一级标题");
            assertTrue(level3Chunk.getTitlePath().contains("二级标题"), "titlePath应包含二级标题");
        }
    }

    // ==================== 第二次改造 Phase 2：无标题文档三级降级拆分 ====================

    @Nested
    @DisplayName("第二次改造 Phase 2: 无标题文档三级降级拆分")
    class SecondPhase2ThreeLevelFallbackTest {

        @Test
        @DisplayName("SP2-1: 强段落分隔（双换行）优先")
        void testStrongParagraphSplitFirst() {
            // 每个段落长度超过阈值，确保不会被累加
            String strongParagraphText = """
                第一段内容，长度适中，这是第一个段落的完整内容描述，长度超过阈值限制。

                第二段内容，长度也适中，这是第二个段落的完整内容描述，长度超过阈值限制。

                第三段内容，长度适中，这是第三个段落的完整内容描述，长度超过阈值限制。
                """;

            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(strongParagraphText)
                    .chunkThreshold(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== SP2-1: 强段落分隔优先 ===");
            System.out.println("chunk数: " + results.size());
            for (ChunkResult r : results) {
                System.out.printf("chunk长度: %d%n", r.getChunkText().length());
            }
            assertTrue(results.size() >= 3, "应按强段落分隔拆分为多个chunk");
        }

        @Test
        @DisplayName("SP2-2: 显著超长段落触发弱段落拆分")
        void testOversizedParagraphTriggersWeakSplit() {
            // 创建一个显著超长的段落（包含单换行）
            String longParaText = "第一行内容，长度适中。\n第二行内容，长度适中。\n第三行内容，长度适中。";
            // 阈值设小，使得段落长度 > threshold * 1.5
            int threshold = 30;

            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(longParaText)
                    .chunkThreshold(threshold)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== SP2-2: 显著超长段落触发弱段落拆分 ===");
            System.out.println("原文长度: " + longParaText.length() + ", 阈值: " + threshold);
            System.out.println("chunk数: " + results.size());
            for (ChunkResult r : results) {
                System.out.printf("chunk长度: %d%n", r.getChunkText().length());
            }

            // 由于段落长度 > threshold * 1.5，应触发弱段落拆分
            assertTrue(results.size() > 1, "超长段落应被拆分");
        }

        @Test
        @DisplayName("SP2-3: 无换行长文本按句子兜底拆分")
        void testNoNewlineTextFallsBackToSentenceSplit() {
            // 创建一个无换行的长文本（包含句子分隔符）
            String longTextNoNewline = "这是第一个句子。这是第二个句子。这是第三个句子。这是第四个句子。这是第五个句子。";
            int threshold = 30;

            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(longTextNoNewline)
                    .chunkThreshold(threshold)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== SP2-3: 无换行长文本按句子兜底拆分 ===");
            System.out.println("原文长度: " + longTextNoNewline.length() + ", 阈值: " + threshold);
            System.out.println("chunk数: " + results.size());
            for (ChunkResult r : results) {
                System.out.printf("chunk: '%s' (长度: %d)%n", r.getChunkText(), r.getChunkText().length());
            }

            // 由于无换行，应按句子分隔符拆分
            assertTrue(results.size() > 1, "无换行长文本应按句子拆分");
            // 验证句子完整性（不以句号中间截断）
            for (ChunkResult r : results) {
                String text = r.getChunkText().trim();
                // 每个chunk应以句子分隔符结尾或为最后一个chunk
                if (!text.isEmpty()) {
                    assertTrue(text.endsWith("。") || text.endsWith(".") || text.endsWith("！") || text.endsWith("？"),
                            "chunk应以句子分隔符结尾: " + text);
                }
            }
        }

        @Test
        @DisplayName("SP2-4: 列表项完整性保护")
        void testListItemIntegrity() {
            String listText = """
                - 列表项1
                - 列表项2
                - 列表项3

                普通段落内容。
                """;

            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(listText)
                    .chunkThreshold(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== SP2-4: 列表项完整性保护 ===");
            for (ChunkResult r : results) {
                System.out.printf("chunk:\n%s%n---\n", r.getChunkText());
            }

            // 验证列表项被识别为一个整体
            ChunkResult listChunk = results.stream()
                    .filter(r -> r.getChunkText().contains("- 列表项"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(listChunk, "应存在包含列表项的chunk");
            // 列表项应在一个chunk中完整出现
            assertTrue(listChunk.getChunkText().contains("- 列表项1") &&
                    listChunk.getChunkText().contains("- 列表项2") &&
                    listChunk.getChunkText().contains("- 列表项3"),
                    "列表项应作为一个整体出现在同一chunk中");
        }
    }

    // ==================== 综合测试 ====================

    @Nested
    @DisplayName("综合测试")
    class EndToEndTest {

        @Test
        @DisplayName("E2E-1: short_sections.md 处理")
        void testShortSectionsEndToEnd() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(shortSectionsContent)
                    .chunkThreshold(1000)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== E2E-1: short_sections.md ===");
            System.out.println("总分块数: " + results.size());
            assertTrue(results.size() > 0);
        }

        @Test
        @DisplayName("E2E-2: long_paragraph.md 处理")
        void testLongParagraphEndToEnd() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(longParagraphContent)
                    .chunkThreshold(500)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== E2E-2: long_paragraph.md ===");
            System.out.println("总分块数: " + results.size());
            long level1Count = results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count();
            assertTrue(level1Count > 0, "超长文档应有 Level1");
        }

        @Test
        @DisplayName("E2E-3: 嵌入式.md 处理")
        void testEmbeddedEndToEnd() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(embeddedContent)
                    .chunkThreshold(1000)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            System.out.println("=== E2E-3: 嵌入式.md ===");
            System.out.println("文件长度: " + embeddedContent.length());
            System.out.println("总分块数: " + results.size());
            assertTrue(results.size() > 0);
        }
    }

    @Test
    @DisplayName("打印测试文件信息")
    void printTestFilesInfo() {
        System.out.println("=== 测试文件信息 ===");
        System.out.println("short_sections.md: " + shortSectionsContent.length());
        System.out.println("long_paragraph.md: " + longParagraphContent.length());
        System.out.println("嵌入式.md: " + embeddedContent.length());
    }
}
