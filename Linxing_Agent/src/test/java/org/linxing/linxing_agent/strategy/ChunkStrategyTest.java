package org.linxing.linxing_agent.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyFactory;
import org.linxing.linxing_agent.rag.strategy.impl.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("分块策略测试")
class ChunkStrategyTest {

    private String markdownContent;
    private String htmlContent;
    private String javaCodeContent;
    private String txtContent;
    private String logContent;
    private String csvContent;
    private String recursiveContent;

    @BeforeEach
    void setUp() throws IOException {
        markdownContent = loadResource("sample.md");
        htmlContent = loadResource("sample.html");
        javaCodeContent = loadResource("SampleCode.java");
        txtContent = loadResource("sample.txt");
        logContent = loadResource("sample.log");
        csvContent = loadResource("sample.csv");
        recursiveContent = loadResource("sample_recursive.txt");
    }

    private String loadResource(String fileName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new IOException("Resource not found: " + fileName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void printChunkResults(String label, List<ChunkResult> results) {
        System.out.println("=== " + label + " ===");
        System.out.println("总分块数: " + results.size());
        long level1Count = results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count();
        long level2Count = results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_2).count();
        System.out.println("Level 1 分块数: " + level1Count);
        System.out.println("Level 2 分块数: " + level2Count);
        for (int i = 0; i < results.size(); i++) {
            ChunkResult r = results.get(i);
            System.out.printf("[%d] Level=%d, Type=%s, TitlePath=%s, ParentId=%s%n",
                    i, r.getChunkLevel(), r.getChunkType(), r.getTitlePath(), r.getParentChunkId());
            System.out.println("  内容预览: " + r.getChunkText().substring(0, Math.min(100, r.getChunkText().length())) + "...");
        }
    }

    @Nested
    @DisplayName("MarkdownChunkStrategy 测试")
    class MarkdownChunkStrategyTest {

        private MarkdownChunkStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new MarkdownChunkStrategy();
        }

        @Test
        @DisplayName("should 支持 Markdown 文件类型")
        void testSupports_MarkdownFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText("test content")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持 markdown 文件类型")
        void testSupports_MarkdownFileTypeAlt() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("markdown")
                    .fullText("test content")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持包含 Markdown 特征的内容")
        void testSupports_MarkdownContent() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fullText("# Title\n\nSome content with ## heading")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 正确分块 Markdown 文档")
        void testExecute_MarkdownDocument() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fileName("sample.md")
                    .fullText(markdownContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            boolean hasTitlePath = results.stream()
                    .anyMatch(r -> r.getTitlePath() != null && !r.getTitlePath().isEmpty());
            assertTrue(hasTitlePath, "应该有包含标题路径的分块");

            printChunkResults("Markdown 分块结果", results);
        }

        @Test
        @DisplayName("should 代码块被识别为 CODE 类型")
        void testExecute_CodeBlockType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(markdownContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            boolean hasCodeType = results.stream()
                    .anyMatch(r -> ChunkType.CODE.equals(r.getChunkType()));
            assertTrue(hasCodeType, "应该有 CODE 类型的分块（代码块应被正确识别）");

            List<ChunkResult> codeChunks = results.stream()
                    .filter(r -> ChunkType.CODE.equals(r.getChunkType()))
                    .collect(Collectors.toList());
            System.out.println("=== 代码块分块 ===");
            for (ChunkResult r : codeChunks) {
                System.out.printf("TitlePath=%s, 内容预览=%s%n", r.getTitlePath(),
                        r.getChunkText().substring(0, Math.min(80, r.getChunkText().length())));
            }
        }

        @Test
        @DisplayName("should 超长 section 生成 L1 父 chunk 和 L2 子 chunk")
        void testExecute_Level1ParentChild() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(markdownContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            long level1Count = results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count();
            assertTrue(level1Count > 0, "应该有 Level 1 父 chunk（超长 section 触发）");

            List<ChunkResult> l2WithParent = results.stream()
                    .filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_2 && r.getParentChunkId() != null)
                    .collect(Collectors.toList());
            assertFalse(l2WithParent.isEmpty(), "应该有带 parentChunkId 的 Level 2 子 chunk");

            System.out.println("=== L1/L2 父子分块 ===");
            System.out.println("L1 父 chunk 数: " + level1Count);
            System.out.println("L2 子 chunk（有父）数: " + l2WithParent.size());
        }

        @Test
        @DisplayName("should 表格被识别为 TABLE 类型")
        void testExecute_TableType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(markdownContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            boolean hasTableType = results.stream()
                    .anyMatch(r -> ChunkType.TABLE.equals(r.getChunkType()));
            assertTrue(hasTableType, "应该有 TABLE 类型的分块（表格应被正确识别）");
        }
    }

    @Nested
    @DisplayName("HtmlChunkStrategy 测试")
    class HtmlChunkStrategyTest {

        private HtmlChunkStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new HtmlChunkStrategy();
        }

        @Test
        @DisplayName("should 支持 HTML 文件类型")
        void testSupports_HtmlFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fullText("<html><body>test</body></html>")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持 htm 文件类型")
        void testSupports_HtmFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("htm")
                    .fullText("<html><body>test</body></html>")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 正确分块 HTML 文档")
        void testExecute_HtmlDocument() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fileName("sample.html")
                    .fullText(htmlContent)
                    .maxChunkSize(1000)
                    .chunkOverlap(100)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            printChunkResults("HTML 分块结果", results);
        }

        @Test
        @DisplayName("should section/article 内标题被提取为 TitlePath")
        void testExecute_SectionTitlePath() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fullText(htmlContent)
                    .maxChunkSize(1000)
                    .chunkOverlap(100)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            boolean hasMeaningfulTitlePath = results.stream()
                    .anyMatch(r -> r.getTitlePath() != null
                            && !r.getTitlePath().equals("section")
                            && !r.getTitlePath().equals("article")
                            && !r.getTitlePath().isEmpty());
            assertTrue(hasMeaningfulTitlePath, "section/article 内的标题应被提取为 TitlePath，不应仅是标签名");

            System.out.println("=== HTML TitlePath 详情 ===");
            for (ChunkResult r : results) {
                System.out.printf("TitlePath=%s, 内容预览=%s%n", r.getTitlePath(),
                        r.getChunkText().substring(0, Math.min(80, r.getChunkText().length())));
            }
        }

        @Test
        @DisplayName("should 超长 section 生成 L1 父 chunk")
        void testExecute_Level1ParentChild() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fullText(htmlContent)
                    .maxChunkSize(1000)
                    .chunkOverlap(100)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            long level1Count = results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count();
            long l2WithParent = results.stream()
                    .filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_2 && r.getParentChunkId() != null)
                    .count();

            System.out.println("=== HTML L1/L2 父子分块 ===");
            System.out.println("L1 父 chunk 数: " + level1Count);
            System.out.println("L2 子 chunk（有父）数: " + l2WithParent);
        }

        @Test
        @DisplayName("should HTML 结构标签被正确剥离")
        void testExecute_HtmlTagStripping() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fullText(htmlContent)
                    .maxChunkSize(1000)
                    .chunkOverlap(100)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            for (ChunkResult r : results) {
                assertFalse(r.getChunkText().contains("<html>"), "分块文本不应包含 <html> 标签");
                assertFalse(r.getChunkText().contains("<body>"), "分块文本不应包含 <body> 标签");
                assertFalse(r.getChunkText().contains("<head>"), "分块文本不应包含 <head> 标签");
            }
        }
    }

    @Nested
    @DisplayName("CodeChunkStrategy 测试")
    class CodeChunkStrategyTest {

        private CodeChunkStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new CodeChunkStrategy();
        }

        @Test
        @DisplayName("should 支持 Java 文件类型")
        void testSupports_JavaFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("java")
                    .fullText("public class Test {}")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持 Python 文件类型")
        void testSupports_PythonFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("py")
                    .fullText("def hello(): pass")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持包含代码特征的内容")
        void testSupports_CodeContent() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fullText("package com.example;\npublic class Test {\n  public void method() {}\n}")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 正确分块 Java 代码")
        void testExecute_JavaCode() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("java")
                    .fileName("SampleCode.java")
                    .fullText(javaCodeContent)
                    .maxChunkSize(1500)
                    .chunkOverlap(0)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            boolean allCodeType = results.stream()
                    .allMatch(r -> ChunkType.CODE.equals(r.getChunkType()));
            assertTrue(allCodeType, "所有分块应该是代码类型");

            printChunkResults("Java 代码分块结果", results);
        }

        @Test
        @DisplayName("should TitlePath 不累积函数名（修复验证）")
        void testExecute_TitlePathNoAccumulation() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("java")
                    .fullText(javaCodeContent)
                    .maxChunkSize(1500)
                    .chunkOverlap(0)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            List<ChunkResult> functionChunks = results.stream()
                    .filter(r -> r.getTitlePath() != null && r.getTitlePath().contains(" > "))
                    .collect(Collectors.toList());

            assertFalse(functionChunks.isEmpty(), "应该有包含类名 > 函数名格式的 TitlePath");

            for (ChunkResult r : functionChunks) {
                String path = r.getTitlePath();
                String[] parts = path.split(" > ");
                assertTrue(parts.length <= 2,
                        "TitlePath 不应累积超过2层（类名 > 函数名），实际: " + path);
            }

            System.out.println("=== TitlePath 累积修复验证 ===");
            for (ChunkResult r : results) {
                if (r.getTitlePath() != null) {
                    System.out.printf("TitlePath=%s%n", r.getTitlePath());
                }
            }
        }

        @Test
        @DisplayName("should 同一类下多个函数 TitlePath 格式一致")
        void testExecute_ConsistentTitlePathInSameClass() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("java")
                    .fullText(javaCodeContent)
                    .maxChunkSize(1500)
                    .chunkOverlap(0)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            List<String> sampleCodePaths = results.stream()
                    .filter(r -> r.getTitlePath() != null && r.getTitlePath().startsWith("SampleCode"))
                    .map(ChunkResult::getTitlePath)
                    .collect(Collectors.toList());

            assertFalse(sampleCodePaths.isEmpty(), "应该有 SampleCode 类的分块");
            for (String path : sampleCodePaths) {
                if (path.contains(" > ")) {
                    assertTrue(path.startsWith("SampleCode > "),
                            "SampleCode 类下的函数 TitlePath 应以 'SampleCode > ' 开头，实际: " + path);
                }
            }

            System.out.println("=== SampleCode 类 TitlePath 一致性 ===");
            sampleCodePaths.forEach(System.out::println);
        }
    }

    @Nested
    @DisplayName("LineBasedChunkStrategy 测试")
    class LineBasedChunkStrategyTest {

        private LineBasedChunkStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new LineBasedChunkStrategy();
        }

        @Test
        @DisplayName("should 支持 txt 文件类型")
        void testSupports_TxtFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fullText("line1\n\nline2\n\nline3")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持 log 文件类型")
        void testSupports_LogFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("log")
                    .fullText("2024-01-01 INFO message\n2024-01-01 ERROR error")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持 csv 文件类型")
        void testSupports_CsvFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("csv")
                    .fullText("a,b,c\n1,2,3\n4,5,6")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 正确分块日志文件")
        void testExecute_LogFile() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("log")
                    .fileName("sample.log")
                    .fullText(logContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            printChunkResults("Log 文件分块结果", results);
        }

        @Test
        @DisplayName("should 正确分块 CSV 文件")
        void testExecute_CsvFile() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("csv")
                    .fileName("sample.csv")
                    .fullText(csvContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            printChunkResults("CSV 文件分块结果", results);
        }

        @Test
        @DisplayName("should 正确分块 TXT 文件")
        void testExecute_TxtFile() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fileName("sample.txt")
                    .fullText(txtContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            printChunkResults("TXT 文件分块结果", results);
        }
    }

    @Nested
    @DisplayName("RecursiveChunkStrategy 测试")
    class RecursiveChunkStrategyTest {

        private RecursiveChunkStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new RecursiveChunkStrategy();
        }

        @Test
        @DisplayName("should 支持所有文件类型（兜底策略）")
        void testSupports_AlwaysTrue() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("unknown")
                    .fullText("any content")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 正确分块普通文本")
        void testExecute_PlainText() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fileName("sample_recursive.txt")
                    .fullText(recursiveContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            boolean allGeneral = results.stream()
                    .allMatch(r -> ChunkType.GENERAL.equals(r.getChunkType()));
            assertTrue(allGeneral, "所有分块应该是通用类型");

            printChunkResults("Recursive 分块结果", results);
        }
    }

    @Nested
    @DisplayName("StructureAwareChunkStrategy 测试")
    class StructureAwareChunkStrategyTest {

        private StructureAwareChunkStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new StructureAwareChunkStrategy();
        }

        @Test
        @DisplayName("should 支持 docx 文件类型")
        void testSupports_DocxFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("docx")
                    .fullText("document content")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 支持 pdf 文件类型")
        void testSupports_PdfFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("pdf")
                    .fullText("document content")
                    .build();
            assertTrue(strategy.supports(context));
        }

        @Test
        @DisplayName("should 不支持其他文件类型")
        void testSupports_OtherFileType() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fullText("document content")
                    .build();
            assertFalse(strategy.supports(context));
        }

        @Test
        @DisplayName("should 正确分块结构化文档")
        void testExecute_StructuredDocument() {
            String structuredContent = "第一章 介绍\n\n这是第一章的内容。\n\n\n第二章 详细说明\n\n这是第二章的内容，包含更多的信息。\n\n\n第三章 总结\n\n这是第三章的内容，用于总结全文。";

            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("docx")
                    .fileName("sample.docx")
                    .fullText(structuredContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);

            assertNotNull(results);
            assertFalse(results.isEmpty());

            printChunkResults("StructureAware 分块结果", results);
        }
    }

    @Nested
    @DisplayName("ChunkStrategyFactory 测试")
    class ChunkStrategyFactoryTest {

        private ChunkStrategyFactory factory;

        @BeforeEach
        void setUp() {
            factory = new ChunkStrategyFactory(
                    new MarkdownChunkStrategy(),
                    new HtmlChunkStrategy(),
                    new CodeChunkStrategy(),
                    new StructureAwareChunkStrategy(),
                    new LineBasedChunkStrategy(),
                    new RecursiveChunkStrategy()
            );
        }

        @Test
        @DisplayName("should 为 Markdown 文件选择 MarkdownChunkStrategy")
        void testGetStrategy_Markdown() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fullText(markdownContent)
                    .build();

            ChunkStrategy strategy = factory.getStrategy(context);
            assertInstanceOf(MarkdownChunkStrategy.class, strategy);
        }

        @Test
        @DisplayName("should 为 HTML 文件选择 HtmlChunkStrategy")
        void testGetStrategy_Html() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fullText(htmlContent)
                    .build();

            ChunkStrategy strategy = factory.getStrategy(context);
            assertInstanceOf(HtmlChunkStrategy.class, strategy);
        }

        @Test
        @DisplayName("should 为 Java 文件选择 CodeChunkStrategy")
        void testGetStrategy_Java() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("java")
                    .fullText(javaCodeContent)
                    .build();

            ChunkStrategy strategy = factory.getStrategy(context);
            assertInstanceOf(CodeChunkStrategy.class, strategy);
        }

        @Test
        @DisplayName("should 为 docx 文件选择 StructureAwareChunkStrategy")
        void testGetStrategy_Docx() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("docx")
                    .fullText("document content")
                    .build();

            ChunkStrategy strategy = factory.getStrategy(context);
            assertInstanceOf(StructureAwareChunkStrategy.class, strategy);
        }

        @Test
        @DisplayName("should 为未知文件类型选择 RecursiveChunkStrategy")
        void testGetStrategy_Unknown() {
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("unknown")
                    .fullText("some content")
                    .build();

            ChunkStrategy strategy = factory.getStrategy(context);
            assertInstanceOf(RecursiveChunkStrategy.class, strategy);
        }
    }
}
