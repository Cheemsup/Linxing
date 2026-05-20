package org.linxing.linxing_agent.rag.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.strategy.RecursiveTextSplitter;
import org.linxing.linxing_agent.rag.strategy.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 分块策略，按标题层级拆分文档，支持 Level 1/2 父子分块和标题路径提取。
 * 总的拆分思路：先按标题拆分，超长 section 会预提取代码块/表格作为原子块保护后再递归拆分普通文本。
 * CHUNK_LEVEL 的级别划分是对于"标题"而言的。
 */
@Slf4j
@Component("markdownChunkStrategy")
public class MarkdownChunkStrategy implements ChunkStrategy {

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CODE_FENCE = Pattern.compile(
            "^```\\w*\\s*$", Pattern.MULTILINE);
    private static final Pattern TABLE_LINE = Pattern.compile(
            "^\\s*\\|.+\\|\\s*$", Pattern.MULTILINE);

    @Override
    public boolean supports(ChunkStrategyContext context) {
        String ext = context.getFileType();
        //先判断文件类型是否是markdown
        if (ext != null && (ext.equalsIgnoreCase("md") || ext.equalsIgnoreCase("markdown"))) {
            return true;//是则直接return true
        }
        String text = context.getFullText();
        if (text != null && text.length() >= 200) {//全文长度大于200，提取特征判断
            String sample = text.substring(0, Math.min(200, text.length()));
            return sample.contains("# ") || sample.contains("## ") || sample.contains("```");
        }
        return text != null && (text.contains("# ") || text.contains("## "));//使用全文判断
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        int maxChunkSize = context.getMaxChunkSize() != null ? context.getMaxChunkSize() : 800;
        int chunkOverlap = context.getChunkOverlap() != null ? context.getChunkOverlap() : 50;
        String fullText = context.getFullText();

        //按照段、句、符号编排的递归拆分器
        RecursiveTextSplitter refinementPipeline = new RecursiveTextSplitter(maxChunkSize, chunkOverlap);

        //先按照标题进行拆分
        List<HeadingSection> sections = splitByHeadings(fullText);

        List<ChunkResult> results = new ArrayList<>();

        for (int i = 0; i < sections.size(); i++) {
            HeadingSection section = sections.get(i);
            String sectionText = section.text().trim();
            if (sectionText.isEmpty()) {
                continue;
            }

            if (sectionText.length() <= maxChunkSize) {
                ChunkResult result = ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                        .chunkText(sectionText)
                        .titlePath(section.titlePath())
                        .chunkType(classifyChunkType(sectionText))
                        .sourceStrategy("MarkdownChunkStrategy")
                        .build();
                results.add(result);
            } else {
                // 超长 section：创建 Level 1 父 chunk，然后预提取原子块再拆分
                int level1Index = results.size();
                String level1Type = buildSectionType(sectionText);
                ChunkResult level1 = ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_1)
                        .chunkText(sectionText)
                        .titlePath(section.titlePath())
                        .chunkType(level1Type)
                        .sourceStrategy("MarkdownChunkStrategy")
                        .build();
                results.add(level1);

                // 预提取代码块/表格作为原子块，普通文本块再走 refinementPipeline 拆分
                List<AtomicBlock> atomicBlocks = extractAtomicBlocks(sectionText);
                for (AtomicBlock block : atomicBlocks) {
                    if (block.isAtomic) {
                        // 原子块（代码/表格）整体保留，不再拆分
                        results.add(ChunkResult.builder()
                                .parentChunkId(level1Index)
                                .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                                .chunkText(block.text)
                                .titlePath(section.titlePath())
                                .chunkType(block.type)
                                .sourceStrategy("MarkdownChunkStrategy")
                                .build());
                    } else {
                        // 普通文本块：按段落→句子→字符递归拆分
                        List<String> subChunks = refinementPipeline.refine(block.text);
                        for (String subText : subChunks) {
                            if (!subText.isBlank()) {
                                results.add(ChunkResult.builder()
                                        .parentChunkId(level1Index)
                                        .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                                        .chunkText(subText)
                                        .titlePath(section.titlePath())
                                        .chunkType(classifyChunkType(subText))
                                        .sourceStrategy("MarkdownChunkStrategy")
                                        .build());
                            }
                        }
                    }
                }
            }
        }

        log.info("MarkdownChunkStrategy 分块完成，共 {} 个片段（{} 个L1 + {} 个L2）",
                results.size(),
                results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count(),
                results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_2).count());

        return results;
    }

    //解析 Markdown 标题，构建层级标题路径，以标题为界拆分文本
    //使用栈来维护层级关系，最后将栈中所有非空标题用 > 连接成 titlePath
    private List<HeadingSection> splitByHeadings(String text) {
        List<HeadingSection> sections = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sections;
        }

        Matcher matcher = HEADING_PATTERN.matcher(text);
        List<int[]> headingPositions = new ArrayList<>();

        while (matcher.find()) {
            headingPositions.add(new int[]{matcher.start(), matcher.end(), matcher.group(1).length(), headingPositions.size()});
        }

        if (headingPositions.isEmpty()) {
            sections.add(new HeadingSection(text, null));
            return sections;
        }

        int firstHeadingStart = headingPositions.get(0)[0];
        if (firstHeadingStart > 0) {
            String preamble = text.substring(0, firstHeadingStart).trim();
            if (!preamble.isEmpty()) {
                sections.add(new HeadingSection(preamble, null));
            }
        }

        String[] titleStack = new String[6];

        for (int i = 0; i < headingPositions.size(); i++) {
            int[] pos = headingPositions.get(i);
            int headingStart = pos[0];
            int headingEnd = pos[1];
            int level = pos[2];
            String headingText = text.substring(headingStart, headingEnd).trim();

            titleStack[level - 1] = headingText.replaceAll("^#+\\s*", "");
            for (int j = level; j < 6; j++) {
                titleStack[j] = null;
            }

            // 构建路径
            StringBuilder titlePath = new StringBuilder();
            for (int j = 0; j < 6; j++) {
                if (titleStack[j] != null) {
                    if (titlePath.length() > 0) {
                        titlePath.append(" > ");
                    }
                    titlePath.append(titleStack[j]);
                }
            }

            int contentStart = headingEnd;
            int contentEnd = (i + 1 < headingPositions.size()) ? headingPositions.get(i + 1)[0] : text.length();
            String content = text.substring(contentStart, contentEnd).trim();
            String fullSection = headingText + "\n" + content;

            sections.add(new HeadingSection(fullSection, titlePath.toString()));
        }

        return sections;
    }

    private String classifyChunkType(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```") || trimmed.contains("\n```")) {
            return ChunkType.CODE;
        }
        if (text.contains("|") && text.contains("---")) {
            return ChunkType.TABLE;
        }
        if (text.matches("(?s)^#[^#].*")) {
            return ChunkType.SECTION;
        }
        return ChunkType.GENERAL;
    }

    private String buildSectionType(String sectionText) {
        if (sectionText.startsWith("```")) {
            return ChunkType.CODE;
        }
        if (TABLE_LINE.matcher(sectionText).find()) {
            return ChunkType.TABLE;
        }
        return ChunkType.SECTION;
    }

    private record AtomicBlock(String text, String type, boolean isAtomic) {}

    /**
     * 提取原子块（代码块、表格）与普通文本的混合列表。
     */
    private List<AtomicBlock> extractAtomicBlocks(String text) {
        // 识别代码块范围（```...```）
        List<int[]> codeRanges = findCodeBlockRanges(text);
        // 识别表格范围（排除代码块内部的表格行）
        List<int[]> tableRanges = findTableRanges(text, codeRanges);
        // 合并代码块和表格范围，按位置排序
        List<int[]> allAtomic = mergeSortedRanges(codeRanges, tableRanges);

        if (allAtomic.isEmpty()) {
            return List.of(new AtomicBlock(text, ChunkType.GENERAL, false));
        }

        List<AtomicBlock> blocks = new ArrayList<>();
        int cursor = 0;
        for (int[] range : allAtomic) {
            // 原子块前的普通文本 → 需要进一步拆分
            if (range[0] > cursor) {
                String before = text.substring(cursor, range[0]).trim();
                if (!before.isEmpty()) {
                    blocks.add(new AtomicBlock(before, ChunkType.GENERAL, false));
                }
            }
            // 原子块 → 整体保留，不再拆分
            String atomicText = text.substring(range[0], range[1]).trim();
            if (!atomicText.isEmpty()) {
                String type = containsRange(range, codeRanges) ? ChunkType.CODE : ChunkType.TABLE;
                blocks.add(new AtomicBlock(atomicText, type, true));
            }
            cursor = range[1];
        }
        // 最后一个原子块后的普通文本 → 需要进一步拆分
        if (cursor < text.length()) {
            String after = text.substring(cursor).trim();
            if (!after.isEmpty()) {
                blocks.add(new AtomicBlock(after, ChunkType.GENERAL, false));
            }
        }
        return blocks;
    }

    /**
     * 查找所有代码块范围（```...``` 配对）。
     * 返回每个代码块的 [起始位置, 结束位置]。
     */
    private List<int[]> findCodeBlockRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        Matcher fenceMatcher = CODE_FENCE.matcher(text);
        List<Integer> fenceStarts = new ArrayList<>();
        List<Integer> fenceEnds = new ArrayList<>();
        while (fenceMatcher.find()) {
            fenceStarts.add(fenceMatcher.start());
            fenceEnds.add(fenceMatcher.end());
        }
        // 成对匹配 fence：第0个开始和第1个结束配对，第2个开始和第3个结束配对...
        for (int i = 0; i + 1 < fenceStarts.size(); i += 2) {
            int openPos = fenceStarts.get(i);
            int closeLineEnd = fenceEnds.get(i + 1);
            // 找到闭合 fence 所在行的末尾
            int closeEnd = text.indexOf('\n', closeLineEnd);
            if (closeEnd == -1) {
                closeEnd = text.length();
            }
            ranges.add(new int[]{openPos, closeEnd});
        }
        return ranges;
    }

    /**
     * 查找所有表格范围（连续的 |...| 行）。
     * 排除代码块内部的表格行，避免误判代码中的 | 符号。
     */
    private List<int[]> findTableRanges(String text, List<int[]> codeRanges) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = TABLE_LINE.matcher(text);
        List<int[]> rowPositions = new ArrayList<>();
        // 收集所有不在代码块内的表格行位置
        while (m.find()) {
            if (!isInsideAny(m.start(), codeRanges)) {
                rowPositions.add(new int[]{m.start(), m.end()});
            }
        }
        if (rowPositions.size() < 2) {
            return ranges;
        }
        // 将连续的表格行聚合成一个表格区域
        int groupStart = rowPositions.get(0)[0];
        int groupEnd = rowPositions.get(0)[1];
        for (int i = 1; i < rowPositions.size(); i++) {
            int[] row = rowPositions.get(i);
            String gap = text.substring(groupEnd, row[0]);
            // 行间只有空白或换行 → 属于同一张表
            if (gap.trim().isEmpty() || gap.matches("\\s*\\n\\s*")) {
                groupEnd = row[1];
            } else {
                // 行间有其他内容 → 新表格开始
                ranges.add(new int[]{groupStart, groupEnd});
                groupStart = row[0];
                groupEnd = row[1];
            }
        }
        ranges.add(new int[]{groupStart, groupEnd});
        return ranges;
    }

    /**
     * 合并两组范围列表，按起始位置排序，并合并重叠区域。
     */
    private List<int[]> mergeSortedRanges(List<int[]> a, List<int[]> b) {
        List<int[]> all = new ArrayList<>();
        all.addAll(a);
        all.addAll(b);
        all.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : all) {
            if (merged.isEmpty() || merged.get(merged.size() - 1)[1] < range[0]) {
                merged.add(range);
            } else {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
            }
        }
        return merged;
    }

    /**
     * 判断某个位置是否落在任一范围内（用于排除代码块内的表格行）。
     */
    private static boolean isInsideAny(int pos, List<int[]> ranges) {
        for (int[] r : ranges) {
            if (pos >= r[0] && pos < r[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断目标范围是否在给定范围列表中（用于区分代码块和表格）。
     */
    private static boolean containsRange(int[] target, List<int[]> ranges) {
        for (int[] r : ranges) {
            if (r[0] == target[0] && r[1] == target[1]) {
                return true;
            }
        }
        return false;
    }

    private record HeadingSection(String text, String titlePath) {}
}
