package org.linxing.linxing_agent.rag.strategy.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 递归文本拆分器，进行细粒度拆分，由策略选择器选择调用。将超长文本按段落→句子→字符的优先级递归拆分
 * 每个级别的条件不满足之后就会调用下一级的拆分方法来进行更细粒度的拆分，如果递归失败还有最后的固定长度拆分的兜底方法
 * 确保每个子块不超过 maxChunkSize。
 *
 * 改进后支持多级段落识别：
 * - 强段落分隔（双换行）：独立的语义块
 * - 弱段落分隔（单换行）：可能是列表项、短句换行等
 * - 列表项识别：以 "- "、"1. "、"* " 开头的行作为原子单元
 *
 * @deprecated 已废弃。超长拆分职责已迁移至 Python 侧 parsers（按句子/逻辑行拆分并标 groupId，
 *             由 NodeBasedChunkBuilder 据 groupId 合成父子装配）。仅服务于已废弃的 HtmlChunkStrategy/
 *             CodeChunkStrategy/StructureAwareChunkStrategy 旧路径，保留仅供历史参考，后续应删除。
 */
@Deprecated
@Slf4j
public class RecursiveTextSplitter {

    private final int maxChunkSize;
    private final int chunkOverlap;

    // 强段落分隔：双换行（独立的语义块）
    private static final String STRONG_PARAGRAPH_SEP = "\n\\s*\n";
    // 弱段落分隔：单换行（可能是列表项、短句换行等）
    private static final String WEAK_PARAGRAPH_SEP = "\n";
    // 句子分隔符
    private static final String SENTENCE_SEP = "(?<=[。！？.!?；;])\\s*";
    // 列表项特征：以 "- "、"1. "、"* " 等开头
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^\\s*[-*•]\\s+|^\\s*\\d+\\.\\s+", Pattern.MULTILINE);

    public RecursiveTextSplitter(int maxChunkSize, int chunkOverlap) {
        this.maxChunkSize = maxChunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public List<String> refine(String text) {
        // 空文本或已满足大小限制，直接返回
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= maxChunkSize) {
            return List.of(text);
        }

        // 递归拆分超长文本
        List<String> results = new ArrayList<>();
        refineRecursive(text, results);
        return results;
    }

    private void refineRecursive(String text, List<String> results) {
        // 文本已满足大小限制，直接收集结果
        if (text.length() <= maxChunkSize) {
            if (!text.isBlank()) {
                results.add(text);
            }
            return;
        }

        // 尝试按改进版段落拆分（优先保持段落完整性）
        List<String> paragraphs = splitParagraphsImproved(text);
        if (paragraphs.size() > 1) {
            // 检查所有段落是否都满足大小限制
            boolean allFit = true;
            for (String p : paragraphs) {
                if (p.trim().length() > maxChunkSize) {
                    allFit = false;
                    break;
                }
            }
            // 所有段落都符合要求，直接收集
            if (allFit) {
                for (String p : paragraphs) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) {
                        results.add(trimmed);
                    }
                }
                return;
            }
            // 部分段落超长，对超长段落继续按句子拆分
            for (String p : paragraphs) {
                if (p.trim().length() > maxChunkSize) {
                    splitSentences(p.trim(), results);
                } else if (!p.trim().isEmpty()) {
                    results.add(p.trim());
                }
            }
            return;
        }

        // 无法按段落拆分，降级为按句子拆分
        splitSentences(text, results);
    }

    /**
     * 改进版段落拆分：多级段落识别策略
     * 1. 先按双换行拆分强段落（独立语义块）
     * 2. 检查是否包含列表项 → 按列表项拆分
     * 3. 段落长度 > maxChunkSize * 1.5 时尝试按单换行拆分（保守策略）
     * 4. 无法确定时保持原段落完整
     */
    private List<String> splitParagraphsImproved(String text) {
        List<String> result = new ArrayList<>();

        // 1. 先按强段落分隔（双换行）拆分
        String[] strongParagraphs = text.split(STRONG_PARAGRAPH_SEP, -1);

        for (String para : strongParagraphs) {
            if (para.isEmpty()) {
                continue;
            }

            // 2. 检查是否包含列表项
            if (containsListItems(para)) {
                // 按列表项拆分，保持每个列表项完整
                List<String> listItems = splitByListItems(para);
                result.addAll(listItems);
                continue;
            }

            // 3. 段落长度 > maxChunkSize * 1.5 时，尝试按单换行拆分（保守策略）
            if (para.length() > maxChunkSize * 1.5) {
                List<String> weakParagraphs = splitByWeakParagraphs(para);
                // 拆分后每个子段落如果仍超长，后续会进入句子拆分
                result.addAll(weakParagraphs);
                continue;
            }

            // 4. 正常长度段落，保持完整
            result.add(para);
        }

        return result;
    }

    /**
     * 检查文本是否包含列表项（以 "- "、"1. "、"* " 开头的行）
     */
    private boolean containsListItems(String text) {
        Matcher matcher = LIST_ITEM_PATTERN.matcher(text);
        return matcher.find();
    }

    /**
     * 按列表项拆分文本，保持每个列表项完整
     * 列表项特征：以 "- "、"1. "、"* " 等开头的行
     */
    private List<String> splitByListItems(String text) {
        List<String> items = new ArrayList<>();
        String[] lines = text.split(WEAK_PARAGRAPH_SEP, -1);

        StringBuilder currentItem = new StringBuilder();
        boolean inListItem = false;

        for (String line : lines) {
            if (line.isEmpty()) {
                // 空行：如果当前有累积的列表项，先保存
                if (inListItem && currentItem.length() > 0) {
                    items.add(currentItem.toString().trim());
                    currentItem.setLength(0);
                    inListItem = false;
                }
                continue;
            }

            // 检查当前行是否是新的列表项起始
            Matcher matcher = LIST_ITEM_PATTERN.matcher(line);
            if (matcher.find()) {
                // 新列表项开始：先保存之前的累积内容
                if (currentItem.length() > 0) {
                    items.add(currentItem.toString().trim());
                }
                currentItem.setLength(0);
                currentItem.append(line);
                inListItem = true;
            } else if (inListItem) {
                // 当前行是列表项的延续内容（非列表项起始行）
                // 拼接到当前列表项（保持完整）
                currentItem.append("\n").append(line);
            } else {
                // 非列表项内容，作为普通段落处理
                if (currentItem.length() > 0) {
                    items.add(currentItem.toString().trim());
                    currentItem.setLength(0);
                }
                items.add(line);
                inListItem = false;
            }
        }

        // 保存最后累积的内容
        if (currentItem.length() > 0) {
            items.add(currentItem.toString().trim());
        }

        return items;
    }

    /**
     * 按弱段落分隔（单换行）拆分文本
     * 保守策略：只在段落显著超长时使用
     */
    private List<String> splitByWeakParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] lines = text.split(WEAK_PARAGRAPH_SEP, -1);

        // 尝试将连续的短行合并为一个段落
        StringBuilder buffer = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) {
                // 空行：刷新缓冲区
                if (buffer.length() > 0) {
                    paragraphs.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                continue;
            }

            // 如果当前行本身就很长（接近 maxChunkSize），单独处理
            if (line.length() > maxChunkSize * 0.8) {
                // 先刷新缓冲区
                if (buffer.length() > 0) {
                    paragraphs.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                // 长行单独作为一个段落
                paragraphs.add(line);
                continue;
            }

            // 短行累积到缓冲区
            if (buffer.length() > 0) {
                buffer.append("\n");
            }
            buffer.append(line);

            // 累积后若超过 maxChunkSize，切出
            if (buffer.length() >= maxChunkSize) {
                paragraphs.add(buffer.toString().trim());
                buffer.setLength(0);
            }
        }

        // 刷新剩余缓冲区
        if (buffer.length() > 0) {
            paragraphs.add(buffer.toString().trim());
        }

        return paragraphs;
    }

    private void splitSentences(String text, List<String> results) {
        // 文本已满足大小限制，直接收集结果
        if (text.length() <= maxChunkSize) {
            if (!text.isBlank()) {
                results.add(text);
            }
            return;
        }

        // 按句子分隔符拆分文本
        List<String> sentences = new ArrayList<>();
        String[] parts = text.split(SENTENCE_SEP, -1);
        for (String part : parts) {
            if (!part.isEmpty()) {
                sentences.add(part.trim());
            }
        }

        // 无法拆分为多个句子，降级为字符级递归拆分
        if (sentences.size() <= 1) {
            recursiveCharSplit(text, results);
            return;
        }

        // 检查所有句子是否都满足大小限制
        boolean allFit = true;
        for (String s : sentences) {
            if (s.length() > maxChunkSize) {
                allFit = false;
                break;
            }
        }

        // 所有句子都符合要求，直接收集
        if (allFit) {
            results.addAll(sentences);
            return;
        }

        // 合并短句；对超长单句使用字符级拆分；相邻 chunk 之间保留尾部句子作为 overlap 重叠
        // 注意：bufferLen 不含分隔符，判断容量时用 bufferSentences.size() 近似分隔符数量（偏保守，更早触发刷新）
        List<String> bufferSentences = new ArrayList<>(); // 当前 chunk 累积的句子
        int bufferLen = 0; // 当前 chunk 累积的字符长度（不含分隔符）
        for (String sentence : sentences) {
            if (sentence.length() > maxChunkSize) { // 单句过长，需字符级拆分
                // 先刷新缓冲区，再对超长单句做字符级拆分
                flushSentenceBuffer(bufferSentences, results);
                bufferSentences.clear();
                bufferLen = 0;
                recursiveCharSplit(sentence, results);
            } else if (bufferLen + bufferSentences.size() + sentence.length() > maxChunkSize) { // 再加一句就超长
                // 缓冲区已满：先获取 overlap（刷新前），再刷新，新 chunk 以 overlap 起始
                List<String> overlap = tailOverlapSentences(bufferSentences);
                flushSentenceBuffer(bufferSentences, results);
                bufferSentences = new ArrayList<>(overlap);
                bufferLen = overlap.stream().mapToInt(String::length).sum();
                // 当前句必然加入新 chunk（即使超长也会被后续逻辑处理）
                bufferSentences.add(sentence);
                bufferLen += sentence.length();
                // 如果新 chunk 仍超长（极端情况：overlap + 当前句 > maxChunkSize）
                // 立即刷新 overlap 部分，单独处理当前句
                int sepLen = bufferSentences.size() - 1;  // 空格分隔符数量
                if (bufferLen + sepLen > maxChunkSize) {
                    flushSentenceBuffer(overlap, results);  // overlap 单独成 chunk
                    bufferSentences.clear();
                    bufferSentences.add(sentence);
                    bufferLen = sentence.length();
                    // 当前句如果仍超长，进入下一轮循环会触发单句拆分逻辑（L147）
                }
            } else { // 句子追加到缓冲区
                bufferSentences.add(sentence);
                bufferLen += sentence.length();
            }
        }
        // 刷新剩余内容
        flushSentenceBuffer(bufferSentences, results);
    }

    /** 将缓冲区句子用空格拼接成一个 chunk 并加入结果 */
    private void flushSentenceBuffer(List<String> sentences, List<String> results) {
        if (sentences.isEmpty()) {
            return;
        }
        String joined = String.join(" ", sentences).trim();
        if (!joined.isEmpty()) {
            results.add(joined);
        }
    }

    /**
     * 从句子列表尾部向前取尽可能多的句子，使累计长度接近 chunkOverlap，作为下一 chunk 的重叠起始。
     * 不会切断单个句子；强制至少携带最后一句（即使超过 chunkOverlap），避免 overlap 落空。
     */
    private List<String> tailOverlapSentences(List<String> sentences) {
        if (sentences.isEmpty()) {
            return new ArrayList<>();
        }
        // chunkOverlap <= 0 时仍携带最后一句（保证 overlap 机制生效）
        if (chunkOverlap <= 0) {
            return new ArrayList<>(sentences.subList(sentences.size() - 1, sentences.size()));
        }
        List<String> tail = new ArrayList<>();
        int len = 0;
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String s = sentences.get(i);
            // 已有携带句且再加会超过 overlap 预算，停止回退
            // 但至少保留最后一句
            if (!tail.isEmpty() && len + s.length() > chunkOverlap) {
                break;
            }
            tail.add(0, s);
            len += s.length();
        }
        // 兜底：如果 tail 为空（理论上不会），强制加入最后一句
        if (tail.isEmpty()) {
            tail.add(sentences.get(sentences.size() - 1));
        }
        return tail;
    }


    //字符级别拆分
    private void recursiveCharSplit(String text, List<String> results) {
        try {
            // 使用 LangChain4j 的递归字符分割器
            Document doc = Document.from(text);
            DocumentSplitter splitter = DocumentSplitters.recursive(maxChunkSize, chunkOverlap);
            List<TextSegment> segments = splitter.split(doc);
            for (TextSegment segment : segments) {
                String segText = segment.text();
                if (segText != null && !segText.isBlank()) {
                    results.add(segText);
                }
            }
        } catch (Exception e) {
            // 递归分割失败，回退为简单的固定长度切割
            log.warn("递归字符分割失败，回退为整段: {}", e.getMessage());
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + maxChunkSize, text.length());
                results.add(text.substring(start, end));
                start = end;
            }
        }
    }
}
