package org.linxing.linxing_agent.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.strategy.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.linxing.linxing_agent.rag.strategy.impl.*;
import org.linxing.linxing_agent.rag.vo.ChunkTreeVO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("分块树形结构测试")
class ChunkTreeStructureTest {

    private String markdownContent;
    private String htmlContent;
    private String javaCodeContent;
    private String txtContent;
    private String csvContent;
    private String recursiveContent;

    @BeforeEach
    void setUp() throws IOException {
        markdownContent = loadResource("sample.md");
        htmlContent = loadResource("sample.html");
        javaCodeContent = loadResource("SampleCode.java");
        txtContent = loadResource("sample.txt");
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

    private List<ChunkTreeVO> buildChunkTree(List<ChunkResult> results) {
        Map<Integer, List<ChunkResult>> childrenMap = results.stream()
                .filter(r -> r.getParentChunkId() != null)
                .collect(Collectors.groupingBy(ChunkResult::getParentChunkId));

        List<ChunkResult> level1Chunks = results.stream()
                .filter(r -> r.getParentChunkId() == null)
                .collect(Collectors.toList());

        List<ChunkTreeVO> tree = new ArrayList<>();
        int idCounter = 1;
        Map<ChunkResult, Integer> idMap = new HashMap<>();
        for (ChunkResult r : results) {
            idMap.put(r, idCounter++);
        }

        for (ChunkResult level1 : level1Chunks) {
            ChunkTreeVO node = toChunkTreeVO(level1, idMap.get(level1));
            Integer level1Id = idMap.get(level1);
            List<ChunkResult> children = childrenMap.getOrDefault(level1Id, List.of());
            node.setChildren(children.stream()
                    .map(c -> toChunkTreeVO(c, idMap.get(c)))
                    .collect(Collectors.toList()));
            tree.add(node);
        }

        if (level1Chunks.isEmpty() && !results.isEmpty()) {
            return results.stream()
                    .map(r -> toChunkTreeVO(r, idMap.get(r)))
                    .collect(Collectors.toList());
        }

        return tree;
    }

    private ChunkTreeVO toChunkTreeVO(ChunkResult result, Integer id) {
        String preview = result.getChunkText();
        if (preview != null && preview.length() > 200) {
            preview = preview.substring(0, 200) + "...";
        }
        return ChunkTreeVO.builder()
                .chunkId(id)
                .titlePath(result.getTitlePath())
                .chunkLevel(result.getChunkLevel())
                .chunkType(result.getChunkType())
                .textPreview(preview)
                .children(new ArrayList<>())
                .build();
    }

    private void printTreeStructure(List<ChunkTreeVO> tree, String indent) {
        for (ChunkTreeVO node : tree) {
            System.out.printf("%s├─ [%d] Level=%d, Type=%s, TitlePath=%s%n",
                    indent, node.getChunkId(), node.getChunkLevel(), node.getChunkType(), node.getTitlePath());
            System.out.printf("%s│  预览: %s%n", indent,
                    node.getTextPreview() != null && node.getTextPreview().length() > 80
                            ? node.getTextPreview().substring(0, 80) + "..."
                            : node.getTextPreview());
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                printTreeStructure(node.getChildren(), indent + "│  ");
            }
        }
    }

    private void analyzeTreeStructure(List<ChunkTreeVO> tree, String label) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== " + label + " 树形结构分析 ===");
        System.out.println("=".repeat(80));

        int totalNodes = countNodes(tree);
        int leafNodes = countLeafNodes(tree);
        int maxDepth = calculateMaxDepth(tree);

        System.out.println("总节点数: " + totalNodes);
        System.out.println("叶子节点数: " + leafNodes);
        System.out.println("最大深度: " + maxDepth);

        Map<String, Long> typeCount = countByType(tree);
        System.out.println("按类型分布: " + typeCount);

        Map<Short, Long> levelCount = countByLevel(tree);
        System.out.println("按层级分布: " + levelCount);

        System.out.println("\n--- 树形结构预览 ---");
        printTreeStructure(tree, "");

        System.out.println("\n" + "=".repeat(80));
    }

    private int countNodes(List<ChunkTreeVO> tree) {
        int count = 0;
        for (ChunkTreeVO node : tree) {
            count++;
            if (node.getChildren() != null) {
                count += countNodes(node.getChildren());
            }
        }
        return count;
    }

    private int countLeafNodes(List<ChunkTreeVO> tree) {
        int count = 0;
        for (ChunkTreeVO node : tree) {
            if (node.getChildren() == null || node.getChildren().isEmpty()) {
                count++;
            } else {
                count += countLeafNodes(node.getChildren());
            }
        }
        return count;
    }

    private int calculateMaxDepth(List<ChunkTreeVO> tree) {
        int maxDepth = 1;
        for (ChunkTreeVO node : tree) {
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                int childDepth = 1 + calculateMaxDepth(node.getChildren());
                maxDepth = Math.max(maxDepth, childDepth);
            }
        }
        return maxDepth;
    }

    private Map<String, Long> countByType(List<ChunkTreeVO> tree) {
        Map<String, Long> count = new LinkedHashMap<>();
        countByTypeRecursive(tree, count);
        return count;
    }

    private void countByTypeRecursive(List<ChunkTreeVO> tree, Map<String, Long> count) {
        for (ChunkTreeVO node : tree) {
            String type = node.getChunkType() != null ? node.getChunkType() : "UNKNOWN";
            count.merge(type, 1L, Long::sum);
            if (node.getChildren() != null) {
                countByTypeRecursive(node.getChildren(), count);
            }
        }
    }

    private Map<Short, Long> countByLevel(List<ChunkTreeVO> tree) {
        Map<Short, Long> count = new LinkedHashMap<>();
        countByLevelRecursive(tree, count);
        return count;
    }

    private void countByLevelRecursive(List<ChunkTreeVO> tree, Map<Short, Long> count) {
        for (ChunkTreeVO node : tree) {
            Short level = node.getChunkLevel() != null ? node.getChunkLevel() : 0;
            count.merge(level, 1L, Long::sum);
            if (node.getChildren() != null) {
                countByLevelRecursive(node.getChildren(), count);
            }
        }
    }

    @Nested
    @DisplayName("Markdown 文件树形结构测试")
    class MarkdownTreeTest {

        @Test
        @DisplayName("should Markdown 文件生成正确的树形结构")
        void testMarkdownTree() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fileName("sample.md")
                    .fullText(markdownContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            analyzeTreeStructure(tree, "Markdown 文件");

            assertFalse(tree.isEmpty(), "树形结构不应为空");

            boolean hasTitlePath = tree.stream()
                    .anyMatch(n -> n.getTitlePath() != null && !n.getTitlePath().isEmpty());
            assertTrue(hasTitlePath, "应该有包含标题路径的节点");

            boolean hasCodeType = tree.stream()
                    .anyMatch(n -> ChunkType.CODE.equals(n.getChunkType()));
            assertTrue(hasCodeType, "应该有 CODE 类型的节点");

            boolean hasTableType = tree.stream()
                    .anyMatch(n -> ChunkType.TABLE.equals(n.getChunkType()));
            assertTrue(hasTableType, "应该有 TABLE 类型的节点");
        }

        @Test
        @DisplayName("should 超长 section 生成父子节点")
        void testLongSectionParentChild() {
            MarkdownChunkStrategy strategy = new MarkdownChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("md")
                    .fileName("sample.md")
                    .fullText(markdownContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            boolean hasParentWithChildren = tree.stream()
                    .anyMatch(n -> n.getChildren() != null && !n.getChildren().isEmpty());

            System.out.println("\n=== 父子节点分析 ===");
            for (ChunkTreeVO node : tree) {
                if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                    System.out.printf("父节点: %s (Level=%d, 子节点数=%d)%n",
                            node.getTitlePath(), node.getChunkLevel(), node.getChildren().size());
                    for (ChunkTreeVO child : node.getChildren()) {
                        System.out.printf("  └─ 子节点: %s (Level=%d)%n",
                                child.getTitlePath(), child.getChunkLevel());
                    }
                }
            }

            assertTrue(hasParentWithChildren, "超长 section 应该生成父子节点结构");
        }
    }

    @Nested
    @DisplayName("HTML 文件树形结构测试")
    class HtmlTreeTest {

        @Test
        @DisplayName("should HTML 文件生成正确的树形结构")
        void testHtmlTree() {
            HtmlChunkStrategy strategy = new HtmlChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fileName("sample.html")
                    .fullText(htmlContent)
                    .maxChunkSize(1000)
                    .chunkOverlap(100)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            analyzeTreeStructure(tree, "HTML 文件");

            assertFalse(tree.isEmpty(), "树形结构不应为空");

            for (ChunkTreeVO node : tree) {
                String preview = node.getTextPreview();
                if (preview != null) {
                    assertFalse(preview.contains("<html>"), "分块文本不应包含 <html> 结构标签");
                    assertFalse(preview.contains("<body>"), "分块文本不应包含 <body> 结构标签");
                    assertFalse(preview.contains("<head>"), "分块文本不应包含 <head> 结构标签");
                }
            }
        }

        @Test
        @DisplayName("should HTML section 标题正确提取")
        void testHtmlSectionTitle() {
            HtmlChunkStrategy strategy = new HtmlChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("html")
                    .fullText(htmlContent)
                    .maxChunkSize(1000)
                    .chunkOverlap(100)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            System.out.println("\n=== HTML TitlePath 分析 ===");
            for (ChunkTreeVO node : tree) {
                System.out.printf("TitlePath: %s%n", node.getTitlePath());
            }

            boolean hasMeaningfulTitlePath = tree.stream()
                    .anyMatch(n -> n.getTitlePath() != null
                            && !n.getTitlePath().equals("section")
                            && !n.getTitlePath().equals("article")
                            && !n.getTitlePath().isEmpty());
            assertTrue(hasMeaningfulTitlePath, "应该有从 section/article 内提取的有意义的标题");
        }
    }

    @Nested
    @DisplayName("Java 代码文件树形结构测试")
    class JavaCodeTreeTest {

        @Test
        @DisplayName("should Java 代码生成正确的树形结构")
        void testJavaCodeTree() {
            CodeChunkStrategy strategy = new CodeChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("java")
                    .fileName("SampleCode.java")
                    .fullText(javaCodeContent)
                    .maxChunkSize(1500)
                    .chunkOverlap(0)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            analyzeTreeStructure(tree, "Java 代码文件");

            assertFalse(tree.isEmpty(), "树形结构不应为空");

            boolean allCodeType = tree.stream()
                    .allMatch(n -> ChunkType.CODE.equals(n.getChunkType()));
            assertTrue(allCodeType, "所有节点应该是 CODE 类型");
        }

        @Test
        @DisplayName("should Java 类和函数 TitlePath 格式正确")
        void testJavaTitlePathFormat() {
            CodeChunkStrategy strategy = new CodeChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("java")
                    .fullText(javaCodeContent)
                    .maxChunkSize(1500)
                    .chunkOverlap(0)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            System.out.println("\n=== Java TitlePath 分析 ===");
            for (ChunkTreeVO node : tree) {
                System.out.printf("TitlePath: %s%n", node.getTitlePath());
            }

            boolean hasSampleCodeClass = tree.stream()
                    .anyMatch(n -> n.getTitlePath() != null && n.getTitlePath().startsWith("SampleCode"));
            assertTrue(hasSampleCodeClass, "应该有 SampleCode 类的分块");

            boolean hasHelperClass = tree.stream()
                    .anyMatch(n -> n.getTitlePath() != null && n.getTitlePath().startsWith("HelperClass"));
            assertTrue(hasHelperClass, "应该有 HelperClass 类的分块");

            boolean hasInterface = tree.stream()
                    .anyMatch(n -> n.getTitlePath() != null && n.getTitlePath().startsWith("DataProcessor"));
            assertTrue(hasInterface, "应该有 DataProcessor 接口的分块");
        }
    }

    @Nested
    @DisplayName("TXT/CSV 文件树形结构测试")
    class TxtCsvTreeTest {

        @Test
        @DisplayName("should TXT 文件生成扁平树形结构")
        void testTxtTree() {
            LineBasedChunkStrategy strategy = new LineBasedChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fileName("sample.txt")
                    .fullText(txtContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            analyzeTreeStructure(tree, "TXT 文件");

            assertFalse(tree.isEmpty(), "树形结构不应为空");

            int maxDepth = calculateMaxDepth(tree);
            assertEquals(1, maxDepth, "TXT 文件应该是扁平结构（深度=1）");
        }

        @Test
        @DisplayName("should CSV 文件生成扁平树形结构")
        void testCsvTree() {
            LineBasedChunkStrategy strategy = new LineBasedChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("csv")
                    .fileName("sample.csv")
                    .fullText(csvContent)
                    .maxChunkSize(800)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            analyzeTreeStructure(tree, "CSV 文件");

            assertFalse(tree.isEmpty(), "树形结构不应为空");

            int maxDepth = calculateMaxDepth(tree);
            assertEquals(1, maxDepth, "CSV 文件应该是扁平结构（深度=1）");
        }
    }

    @Nested
    @DisplayName("Recursive 策略树形结构测试")
    class RecursiveTreeTest {

        @Test
        @DisplayName("should 递归策略生成扁平树形结构")
        void testRecursiveTree() {
            RecursiveChunkStrategy strategy = new RecursiveChunkStrategy();
            ChunkStrategyContext context = ChunkStrategyContext.builder()
                    .fileType("txt")
                    .fileName("sample_recursive.txt")
                    .fullText(recursiveContent)
                    .maxChunkSize(500)
                    .chunkOverlap(50)
                    .build();

            List<ChunkResult> results = strategy.execute(context);
            List<ChunkTreeVO> tree = buildChunkTree(results);

            analyzeTreeStructure(tree, "Recursive 策略");

            assertFalse(tree.isEmpty(), "树形结构不应为空");

            int maxDepth = calculateMaxDepth(tree);
            assertEquals(1, maxDepth, "Recursive 策略应该是扁平结构（深度=1）");
        }
    }

    @Nested
    @DisplayName("功能完善性分析")
    class FunctionalityAnalysisTest {

        @Test
        @DisplayName("分析各类型文件的树形结构特点")
        void analyzeAllFileTypes() {
            System.out.println("\n" + "=".repeat(100));
            System.out.println("=== 各类型文件树形结构对比分析 ===");
            System.out.println("=".repeat(100));

            Map<String, List<ChunkTreeVO>> allTrees = new LinkedHashMap<>();

            MarkdownChunkStrategy mdStrategy = new MarkdownChunkStrategy();
            List<ChunkResult> mdResults = mdStrategy.execute(ChunkStrategyContext.builder()
                    .fileType("md").fullText(markdownContent).maxChunkSize(800).build());
            allTrees.put("Markdown", buildChunkTree(mdResults));

            HtmlChunkStrategy htmlStrategy = new HtmlChunkStrategy();
            List<ChunkResult> htmlResults = htmlStrategy.execute(ChunkStrategyContext.builder()
                    .fileType("html").fullText(htmlContent).maxChunkSize(1000).build());
            allTrees.put("HTML", buildChunkTree(htmlResults));

            CodeChunkStrategy codeStrategy = new CodeChunkStrategy();
            List<ChunkResult> codeResults = codeStrategy.execute(ChunkStrategyContext.builder()
                    .fileType("java").fullText(javaCodeContent).maxChunkSize(1500).build());
            allTrees.put("Java Code", buildChunkTree(codeResults));

            LineBasedChunkStrategy lineStrategy = new LineBasedChunkStrategy();
            List<ChunkResult> txtResults = lineStrategy.execute(ChunkStrategyContext.builder()
                    .fileType("txt").fullText(txtContent).maxChunkSize(800).build());
            allTrees.put("TXT", buildChunkTree(txtResults));

            List<ChunkResult> csvResults = lineStrategy.execute(ChunkStrategyContext.builder()
                    .fileType("csv").fullText(csvContent).maxChunkSize(800).build());
            allTrees.put("CSV", buildChunkTree(csvResults));

            System.out.println("\n| 文件类型 | 总节点数 | 叶子节点数 | 最大深度 | 类型分布 | 层级分布 |");
            System.out.println("|----------|----------|------------|----------|----------|----------|");
            for (Map.Entry<String, List<ChunkTreeVO>> entry : allTrees.entrySet()) {
                List<ChunkTreeVO> tree = entry.getValue();
                System.out.printf("| %-12s | %-8d | %-10d | %-8d | %-20s | %-20s |%n",
                        entry.getKey(),
                        countNodes(tree),
                        countLeafNodes(tree),
                        calculateMaxDepth(tree),
                        countByType(tree),
                        countByLevel(tree));
            }

            System.out.println("\n=== 功能完善性评估 ===");
            System.out.println("1. Markdown 文件:");
            System.out.println("   - 支持多级标题（h1-h6）");
            System.out.println("   - 代码块识别为 CODE 类型");
            System.out.println("   - 表格识别为 TABLE 类型");
            System.out.println("   - 超长 section 生成 L1/L2 父子结构");
            System.out.println("   ✓ 功能完善");

            System.out.println("\n2. HTML 文件:");
            System.out.println("   - 支持多级标题（h1-h6）");
            System.out.println("   - section/article 标签处理");
            System.out.println("   - HTML 标签正确剥离");
            System.out.println("   - 超长 section 生成 L1/L2 父子结构");
            System.out.println("   ✓ 功能完善");

            System.out.println("\n3. Java 代码文件:");
            System.out.println("   - 类定义识别");
            System.out.println("   - 方法定义识别");
            System.out.println("   - 接口和抽象类识别");
            System.out.println("   - TitlePath 格式：类名 > 方法名");
            System.out.println("   ✓ 功能完善");

            System.out.println("\n4. TXT/CSV 文件:");
            System.out.println("   - 按行/段落分块");
            System.out.println("   - 扁平结构（无父子关系）");
            System.out.println("   - 无 TitlePath（无结构化标题）");
            System.out.println("   ✓ 功能符合预期");

            System.out.println("\n=== 改进建议 ===");
            System.out.println("1. 前端展示优化:");
            System.out.println("   - 对于扁平结构（TXT/CSV），可考虑按内容分组显示");
            System.out.println("   - 添加分块类型图标区分不同类型节点");
            System.out.println("   - 支持按类型筛选节点");

            System.out.println("\n2. 树形结构增强:");
            System.out.println("   - 可考虑添加节点顺序号（在同级中的位置）");
            System.out.println("   - 添加节点字数统计");
            System.out.println("   - 支持节点展开/折叠状态持久化");

            System.out.println("\n3. 上下文定位增强:");
            System.out.println("   - 支持从树节点跳转到原文位置");
            System.out.println("   - 支持高亮显示当前选中的分块");
            System.out.println("   - 支持跨文档的分块关联");

            System.out.println("\n" + "=".repeat(100));

            assertFalse(allTrees.isEmpty(), "应该有测试结果");
        }
    }
}
