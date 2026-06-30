package org.linxing.linxing_agent.rag.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
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
 * 总的拆分思路：按标题拆分，一般以标题区块作为chunk单位，超长 section 会被递归为使用句子拆分方式进行拆分并维护父子chunk关系；无标题部分则尝试构建"按多换行/双换行——按单换行——按句子"的优先级拆分方式
 * 使用"最长chunk长度"作为阈值、使用标题划分层级（最低三级）作为区块划分动作的指导
 */
@Slf4j
@Component("markdownChunkStrategy")
public class MarkdownChunkStrategy implements ChunkStrategy {

    // 只识别一二三级标题（#{1,3}）
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);
    // DJKJ：保留 sqliteCodeFence 和 TABLE_LINE 用于测试和注释，实际已废弃
    private static final Pattern CODE_FENCE = Pattern.compile(
            "^```\\w*\\s*$", Pattern.MULTILINE);
    private static final Pattern TABLE_LINE = Pattern.compile(
            "^\\s*\\|.+\\|\\s*$", Pattern.MULTILINE);

    // 句子分隔符（中英文）
    private final static Pattern SENTENCE_DELIMITER = Pattern.compile(
            "[。！？.!?；;]");

    // 强段落分隔：多换行/双换行（独立语义块），连续任意数量的换行均视为一个强段落边界
    private static final String STRONG_PARAGRAPH_SEP = "\\n\\s*\\n";

    private record HeadingSection(String text, String titlePath) {}


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
        // 使用 CHUNK_THRESHOLD 作为标题区块拆分阈值（默认 1000，可配置）
        int threshold = context.getChunkThreshold() != null ? context.getChunkThreshold() : RagParameters.CHUNK_THRESHOLD;
        String fullText = context.getFullText();

        // 先按照标题进行拆分
        List<HeadingSection> sections = splitByHeadings(fullText);

        List<ChunkResult> results = new ArrayList<>();

        for (HeadingSection section : sections) {
            String sectionText = section.text().trim();
            if (sectionText.isEmpty()) {
                continue;
            }

            if (sectionText.length() <= threshold) {
                // 短标题区块：直接作为 Level 2 chunk
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
                // 超长标题区块：创建 Level 1 父 chunk + 按句子拆分为 Level 2 子 chunk
                int level1Index = results.size();
                ChunkResult level1 = ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_1)
                        .chunkText(sectionText)
                        .titlePath(section.titlePath())
                        .chunkType(ChunkType.SECTION)
                        .sourceStrategy("MarkdownChunkStrategy")
                        .build();
                results.add(level1);

                List<String> subChunks = splitBySentenceWithThreshold(sectionText, threshold);
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

        log.info("MarkdownChunkStrategy 分块完成，共 {} 个片段（{} 个L1 + {} 个L2）",
                results.size(),
                results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_1).count(),
                results.stream().filter(r -> r.getChunkLevel() == RagParameters.CHUNK_LEVEL_2).count());

        return results;
    }

    /**
     * 按句子分隔符拆分文本，累加句子直到最接近阈值，不切断单个句子。
     * 句子是原子单位，不会被截断。
     */
    private List<String> splitBySentenceWithThreshold(String text, int threshold) {
        List<String> results = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return results;
        }

        // 按句子分隔符拆分，保留分隔符
        List<String> sentences = splitIntoSentences(text);

        StringBuilder buffer = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.isBlank()) {
                continue;
            }

            // 检查累加后是否超过阈值
            int addLen = sentence.length();
            if (buffer.length() + addLen > threshold && buffer.length() > 0) {
                // 超过阈值，先输出当前 buffer
                results.add(buffer.toString().trim());
                buffer = new StringBuilder(sentence);
            } else {
                buffer.append(sentence);
            }
        }

        // 输出剩余 buffer
        if (buffer.length() > 0) {
            results.add(buffer.toString().trim());
        }

        return results;
    }

    /**
     * 按句子分隔符拆分文本，保留分隔符在句子末尾。
     */
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sentences;
        }

        Matcher matcher = SENTENCE_DELIMITER.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            int delimiterEnd = matcher.end();
            String sentence = text.substring(lastEnd, delimiterEnd);
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
            lastEnd = delimiterEnd;
        }

        // 剩余部分（无分隔符的结尾）
        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd);
            if (!remaining.isBlank()) {
                sentences.add(remaining);
            }
        }

        return sentences;
    }

    /**
     * 解析 Markdown 标题（只识别一二三级），构建层级标题路径，以标题为界拆分文本。
     * 使用简单的状态记录维护层级关系（最多三层），titlePath 格式为 "一级标题 > 二级标题 > 三级标题"。
     */
    private List<HeadingSection> splitByHeadings(String text) {
        List<HeadingSection> sections = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sections;
        }

        Matcher matcher = HEADING_PATTERN.matcher(text);
        List<int[]> headingPositions = new ArrayList<>();

        while (matcher.find()) {
            int level = matcher.group(1).length();
            headingPositions.add(new int[]{matcher.start(), matcher.end(), level});
        }

        if (headingPositions.isEmpty()) {
            // 无标题文档：调用专门处理方法
            return processNoTitleDocument(text, RagParameters.CHUNK_THRESHOLD);
        }

        // 处理第一个标题前的 preamble（前置文本）
        int firstHeadingStart = headingPositions.get(0)[0];
        if (firstHeadingStart > 0) {
            String preamble = text.substring(0, firstHeadingStart).trim();
            if (!preamble.isEmpty()) {
                sections.add(new HeadingSection(preamble, null));
            }
        }

        // 只跟踪一二三级标题（最多三层）
        String[] titleStack = new String[3];

        for (int i = 0; i < headingPositions.size(); i++) {
            int[] pos = headingPositions.get(i);
            int headingStart = pos[0];
            int headingEnd = pos[1];
            int level = pos[2];
            String headingText = text.substring(headingStart, headingEnd).trim();

            // 更新标题栈（level 为 1-3）
            titleStack[level - 1] = headingText.replaceAll("^#+\\s*", "");
            // 清空当前级别以下的标题
            for (int j = level; j < 3; j++) {
                titleStack[j] = null;
            }

            // 构建 titlePath（最多三层）
            StringBuilder titlePath = new StringBuilder();
            for (int j = 0; j < 3; j++) {
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

            // 空标题区块：跳过不生成独立 section，但 titleStack 已记录该标题
            if (content.isEmpty()) {
                continue;
            }

            String fullSection = headingText + "\n" + content;

            sections.add(new HeadingSection(fullSection, titlePath.toString()));
        }

        return sections;
    }

    /**
     * 处理无标题文档：三级降级拆分（强段落，多换行/双换行 → 弱段落，单换行 → 句子）+ 阈值累加。
     *
     *
     * 拆分后的子块累加到阈值后输出为一个 chunk（无父子层级，都是 Level2）。
     */
    private List<HeadingSection> processNoTitleDocument(String text, int threshold) {
        List<HeadingSection> sections = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sections;
        }

        // 1. 按强段落分隔（多换行/双换行）拆分
        String[] strongParagraphs = text.split(STRONG_PARAGRAPH_SEP);

        StringBuilder buffer = new StringBuilder();
        for (String para : strongParagraphs) {
            String trimmedPara = para.trim();
            if (trimmedPara.isEmpty()) {
                continue;
            }

            // 2. 检查段落是否显著超长（> threshold * 1.5）
            if (trimmedPara.length() > threshold * 1.5) {
                // 先输出当前 buffer
                if (buffer.length() > 0) {
                    sections.add(new HeadingSection(buffer.toString().trim(), null));
                    buffer.setLength(0);
                }
                // 三级降级拆分：强段落 → 弱段落 → 句子
                List<String> subChunks = splitOversizedParagraph(trimmedPara, threshold);
                for (String sub : subChunks) {
                    if (sub != null && !sub.isBlank()) {
                        sections.add(new HeadingSection(sub.trim(), null));
                    }
                }
            } else {
                // 3. 正常段落：累加到阈值
                int addLen = trimmedPara.length() + (buffer.length() > 0 ? 2 : 0);
                if (buffer.length() + addLen > threshold && buffer.length() > 0) {
                    // 超过阈值，先输出当前 buffer
                    sections.add(new HeadingSection(buffer.toString().trim(), null));
                    buffer = new StringBuilder(trimmedPara);
                } else {
                    if (buffer.length() > 0) {
                        buffer.append("\n\n");
                    }
                    buffer.append(trimmedPara);
                }
            }
        }

        // 输出剩余 buffer
        if (buffer.length() > 0) {
            sections.add(new HeadingSection(buffer.toString().trim(), null));
        }

        return sections;
    }

    /**
     * 拆分显著超长段落：先尝试按单换行拆分为弱段落，无换行或拆分后仍超长则按句子兜底拆分。
     */
    private List<String> splitOversizedParagraph(String para, int threshold) {
        // 1. 尝试按单换行拆分为弱段落（识别并保持列表项完整性）
        if (para.contains("\n")) {
            List<String> weakParagraphs = splitByWeakParagraphs(para);
            // 检查是否所有弱段落都满足阈值
            boolean allFit = weakParagraphs.stream().allMatch(p -> p.length() <= threshold);
            if (allFit) {
                // 累加到阈值后输出
                return accumulateWithThreshold(weakParagraphs, threshold);
            }
        }

        // 2. 无换行或拆分后仍超长 → 复用已有的 splitBySentenceWithThreshold() 方法作为句子级兜底
        return splitBySentenceWithThreshold(para, threshold);
    }

    /**
     * 按单换行拆分弱段落，识别并保持列表项完整性。
     * 连续的列表项（- / * / + / 数字序号）合并为一个区块，不被拆散。
     */
    private List<String> splitByWeakParagraphs(String para) {
        List<String> weakParagraphs = new ArrayList<>();
        String[] lines = para.split("\n", -1);

        StringBuilder blockBuffer = new StringBuilder();
        boolean inList = false;

        for (String line : lines) {
            String trimmedLine = line.trim();
            boolean isListItem = isListItem(trimmedLine);

            if (isListItem) {
                // 当前行是列表项
                if (!inList && blockBuffer.length() > 0) {
                    // 之前的非列表内容先输出
                    weakParagraphs.add(blockBuffer.toString().trim());
                    blockBuffer = new StringBuilder();
                }
                inList = true;
                if (blockBuffer.length() > 0) {
                    blockBuffer.append("\n");
                }
                blockBuffer.append(line);
            } else {
                // 当前行不是列表项
                if (inList && !trimmedLine.isEmpty()) {
                    // 列表结束（遇到非空非列表行），输出列表块
                    if (blockBuffer.length() > 0) {
                        weakParagraphs.add(blockBuffer.toString().trim());
                        blockBuffer = new StringBuilder();
                    }
                    inList = false;
                }
                if (trimmedLine.isEmpty()) {
                    // 空行：段落分隔，输出当前块
                    if (blockBuffer.length() > 0) {
                        weakParagraphs.add(blockBuffer.toString().trim());
                        blockBuffer = new StringBuilder();
                    }
                    inList = false;
                } else {
                    // 普通文本行
                    if (blockBuffer.length() > 0) {
                        blockBuffer.append("\n");
                    }
                    blockBuffer.append(line);
                }
            }
        }

        // 输出剩余
        if (blockBuffer.length() > 0) {
            weakParagraphs.add(blockBuffer.toString().trim());
        }

        return weakParagraphs;
    }

    /**
     * 判断一行是否为 Markdown 列表项。
     * 支持无序列表（- / * / +）和有序列表（1. / 2. 等）。
     */
    private boolean isListItem(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        // 无序列表：- * +
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return true;
        }
        // 有序列表：1. 2. 等数字序号
        if (line.matches("^\\d+\\.\\s+.+")) {
            return true;
        }
        return false;
    }

    /**
     * 将拆分后的子块累加到阈值后输出。
     * 子块之间以单换行连接，累加超过阈值则切出当前块。
     */
    private List<String> accumulateWithThreshold(List<String> chunks, int threshold) {
        List<String> results = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            return results;
        }

        StringBuilder buffer = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk == null || chunk.trim().isEmpty()) {
                continue;
            }
            String trimmed = chunk.trim();
            // 单换行分隔长度为 1
            int addLen = trimmed.length() + (buffer.length() > 0 ? 1 : 0);
            if (buffer.length() + addLen > threshold && buffer.length() > 0) {
                results.add(buffer.toString().trim());
                buffer = new StringBuilder(trimmed);
            } else {
                if (buffer.length() > 0) {
                    buffer.append("\n");
                }
                buffer.append(trimmed);
            }
        }

        if (buffer.length() > 0) {
            results.add(buffer.toString().trim());
        }

        return results;
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

    /**
     * 原子块记录：携带文本、类型、是否为原子块（代码/表格）。
     *
     * @deprecated 已废弃复杂的原子区块识别逻辑。原子区块现统一以标题层级的"管辖内容"为标准，
     *             不再单独识别代码块/表格作为原子块保护。保留仅供历史参考，后续应删除。
     */
    @Deprecated
    private record AtomicBlock(String text, String type, boolean isAtomic) {}

    /**
     * 提取原子块（代码块、表格）与普通文本的混合列表。
     *
     * @deprecated 已废弃。原子区块现统一以标题层级的"管辖内容"为标准，不再预提取代码块/表格。
     *             超长标题区块直接调用 {@link #splitBySentenceWithThreshold} 按句子拆分。
     */
    @Deprecated
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
     *
     * @deprecated 已废弃。仅服务于 {@link #extractAtomicBlocks}，该原子块识别逻辑已不再使用。
     */
    @Deprecated
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
     *
     * @deprecated 已废弃。仅服务于 {@link #extractAtomicBlocks}，该原子块识别逻辑已不再使用。
     */
    @Deprecated
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
     *
     * @deprecated 已废弃。仅服务于 {@link #extractAtomicBlocks}，该原子块识别逻辑已不再使用。
     */
    @Deprecated
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
     *
     * @deprecated 已废弃。仅服务于 {@link #extractAtomicBlocks}，该原子块识别逻辑已不再使用。
     */
    @Deprecated
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
     *
     * @deprecated 已废弃。仅服务于 {@link #extractAtomicBlocks}，该原子块识别逻辑已不再使用。
     */
    @Deprecated
    private static boolean containsRange(int[] target, List<int[]> ranges) {
        for (int[] r : ranges) {
            if (r[0] == target[0] && r[1] == target[1]) {
                return true;
            }
        }
        return false;
    }

}