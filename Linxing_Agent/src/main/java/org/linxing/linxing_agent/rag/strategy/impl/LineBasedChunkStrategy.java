package org.linxing.linxing_agent.rag.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行式分块策略，面向 log/csv/txt 等行式文本
 *
 * 采用"多换行符→单换行符→句子"三级降级拆分策略：
 * 1. 按强段落（多换行/双换行）拆分，累加到阈值输出为 chunk
 * 2. 显著超长段落（> threshold * 1.5）→ 按弱段落（单换行）拆分并累加
 * 3. 弱段落仍超长 → 按句子拆分并累加
 *
 * 使用阈值累加机制，保障段落不断裂的情况下，从上到下收集并组为不超过阈值的段作为一个 chunk
 *
 * @deprecated 已废弃。行式文本三级降级拆分已迁移至 Python 侧
 *             {@code document_analysis_service/parsers/linebased_parser.py}（标准库 re），
 *             由 NodeBasedChunkBuilder 装箱。保留仅供历史参考，后续应删除。
 */
@Deprecated
@Slf4j
@Component("lineBasedChunkStrategy")
public class LineBasedChunkStrategy implements ChunkStrategy {

    private static final Set<String> LINE_BASED_EXTENSIONS = Set.of("log", "csv", "tsv", "txt");
    // 强段落分隔：双换行或多换行（独立语义块）
    private static final String STRONG_PARAGRAPH_SEP = "\\n\\s*\\n";
    // 句子分隔符（中英文）
    private static final Pattern SENTENCE_DELIMITER = Pattern.compile("[。！？.!?；;]");

    @Override
    public boolean supports(ChunkStrategyContext context) {
        String ext = context.getFileType();
        if (ext != null && LINE_BASED_EXTENSIONS.contains(ext.toLowerCase())) {
            return true;
        }

        String text = context.getFullText();
        if (text == null || text.isEmpty()) {
            return false;
        }

        if (isLineBasedContent(text)) {
            return true;
        }

        if (hasFrequentBlankLines(text)) {
            return true;
        }

        return false;
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        // 使用 CHUNK_THRESHOLD 作为拆分阈值（默认 600，可配置）
        int threshold = context.getChunkThreshold() != null ? context.getChunkThreshold() : RagParameters.CHUNK_THRESHOLD;
        String fullText = context.getFullText();

        // 采用三级降级拆分策略：强段落 → 弱段落 → 句子
        List<String> chunks = splitWithThreeLevelStrategy(fullText, threshold);

        List<ChunkResult> results = new ArrayList<>();
        for (String chunkText : chunks) {
            if (chunkText == null || chunkText.isBlank()) {
                continue;
            }
            results.add(ChunkResult.builder()
                    .parentChunkId(null)
                    .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                    .chunkText(chunkText.trim())
                    .titlePath(null)
                    .chunkType(ChunkType.GENERAL)
                    .sourceStrategy("LineBasedChunkStrategy")
                    .build());
        }

        log.info("LineBasedChunkStrategy 分块完成，共 {} 个片段", results.size());
        return results;
    }

    /**
     * 三级降级拆分策略：强段落（多换行）→ 弱段落（单换行）→ 句子
     * 累加机制：从上到下收集并组为不超过阈值的段作为一个 chunk
     */
    private List<String> splitWithThreeLevelStrategy(String text, int threshold) {
        List<String> results = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return results;
        }

        // 1. 按强段落分隔（双换行/多换行）拆分
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
                    results.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                // 三级降级拆分：强段落 → 弱段落 → 句子
                List<String> subChunks = splitOversizedParagraph(trimmedPara, threshold);
                for (String sub : subChunks) {
                    if (sub != null && !sub.isBlank()) {
                        results.add(sub.trim());
                    }
                }
            } else {
                // 3. 正常段落：累加到阈值
                int addLen = trimmedPara.length() + (buffer.length() > 0 ? 2 : 0);
                if (buffer.length() + addLen > threshold && buffer.length() > 0) {
                    // 超过阈值，先输出当前 buffer
                    results.add(buffer.toString().trim());
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
            results.add(buffer.toString().trim());
        }

        return results;
    }

    /**
     * 拆分显著超长段落：先尝试按单换行拆分为弱段落，
     * 无换行或拆分后仍超长则按句子兜底拆分。
     */
    private List<String> splitOversizedParagraph(String para, int threshold) {
        // 1. 尝试按单换行拆分为弱段落
        if (para.contains("\n")) {
            List<String> weakParagraphs = splitByWeakParagraphs(para);
            // 检查是否所有弱段落都满足阈值
            boolean allFit = weakParagraphs.stream().allMatch(p -> p.length() <= threshold);
            if (allFit) {
                // 累加到阈值后输出
                return accumulateWithThreshold(weakParagraphs, threshold);
            }
        }

        // 2. 无换行或拆分后仍超长 → 按句子拆分作为兜底
        return splitBySentenceWithThreshold(para, threshold);
    }

    /**
     * 按单换行拆分弱段落，保持列表项完整性。
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
     * 判断一行是否为列表项。
     * 支持无序列表（- / * / +）和有序列表（1. / 2. 等数字序号）。
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
     * 判断文本是否为行式内容（行长度方差小）。
     */
    private boolean isLineBasedContent(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 5) {
            return false;
        }

        int nonEmptyCount = 0;
        double totalLen = 0;
        double sumSquaredLen = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                nonEmptyCount++;
                totalLen += trimmed.length();
                sumSquaredLen += trimmed.length() * trimmed.length();
            }
        }

        if (nonEmptyCount < 3) {
            return false;
        }

        double meanLen = totalLen / nonEmptyCount;
        double variance = (sumSquaredLen / nonEmptyCount) - (meanLen * meanLen);

        return variance < meanLen * meanLen * 0.5;
    }

    /**
     * 判断文本是否有频繁的空白行块（>= 2 连续空行）。
     */
    private boolean hasFrequentBlankLines(String text) {
        int blankLineBlockCount = 0;
        boolean inBlank = false;
        int consecutiveBlank = 0;

        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                if (!inBlank) {
                    inBlank = true;
                    consecutiveBlank = 1;
                } else {
                    consecutiveBlank++;
                }
            } else {
                if (inBlank && consecutiveBlank >= 2) {
                    blankLineBlockCount++;
                }
                inBlank = false;
                consecutiveBlank = 0;
            }
        }

        return blankLineBlockCount >= 3;
    }

    // ==================== 废弃方法 ====================

    /**
     * 旧版分块执行方法：按空行分段落，再对超长段落使用 RecursiveTextSplitter 进行细化拆分。
     *
     * @deprecated 已废弃。现采用三级降级拆分策略（强段落→弱段落→句子）+ 阈值累加机制，
     *             保障段落不断裂的情况下收集组装生成 chunk。请使用 {@link #execute(ChunkStrategyContext)}。
     */
    @Deprecated
    public List<ChunkResult> executeOld(ChunkStrategyContext context) {
        int maxChunkSize = context.getMaxChunkSize() != null ? context.getMaxChunkSize() : 800;
        int chunkOverlap = context.getChunkOverlap() != null ? context.getChunkOverlap() : 50;
        String fullText = context.getFullText();

        RecursiveTextSplitter refinementPipeline =
                new RecursiveTextSplitter(maxChunkSize, chunkOverlap);

        List<String> paragraphs = splitByBlankLinesOld(fullText);
        List<ChunkResult> results = new ArrayList<>();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.length() <= maxChunkSize) {
                results.add(ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                        .chunkText(trimmed)
                        .titlePath(null)
                        .chunkType(trimmed.startsWith("#") ? ChunkType.SECTION : ChunkType.GENERAL)
                        .sourceStrategy("LineBasedChunkStrategy")
                        .build());
            } else {
                List<String> subChunks = refinementPipeline.refine(trimmed);
                for (String subText : subChunks) {
                    if (!subText.isBlank()) {
                        results.add(ChunkResult.builder()
                                .parentChunkId(null)
                                .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                                .chunkText(subText)
                                .titlePath(null)
                                .chunkType(ChunkType.GENERAL)
                                .sourceStrategy("LineBasedChunkStrategy")
                                .build());
                    }
                }
            }
        }

        log.info("LineBasedChunkStrategy（旧版）分块完成，共 {} 个片段", results.size());
        return results;
    }

    /**
     * 旧版按空行分段落方法。
     *
     * @deprecated 已废弃。仅服务于 {@link #executeOld}。请使用 {@link #splitWithThreeLevelStrategy}。
     */
    @Deprecated
    private List<String> splitByBlankLinesOld(String text) {
        List<String> paragraphs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return paragraphs;
        }
        String[] parts = text.split("\\n\\s*\\n", -1);
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                paragraphs.add(part);
            }
        }
        return paragraphs;
    }
}
