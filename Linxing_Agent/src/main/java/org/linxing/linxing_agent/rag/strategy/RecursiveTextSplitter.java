package org.linxing.linxing_agent.rag.strategy;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归文本拆分器，进行细粒度拆分，由策略选择器选择调用。将超长文本按段落→句子→字符的优先级递归拆分
 * 每个级别的条件不满足之后就会调用下一级的拆分方法来进行更细粒度的拆分，如果递归失败还有最后的固定长度拆分的兜底方法
 * 确保每个子块不超过 maxChunkSize。
 */
@Slf4j
public class RecursiveTextSplitter {

    private final int maxChunkSize;
    private final int chunkOverlap;

    private static final String PARAGRAPH_SEP = "\n\\s*\n";
    private static final String SENTENCE_SEP = "(?<=[。！？.!?；;])\\s*";

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

        // 尝试按段落拆分（优先保持段落完整性）
        List<String> paragraphs = splitParagraphs(text);
        if (paragraphs.size() > 1) {//整段文本超过一段，先按照段落拆分
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

    //将文本按照段落拆分
    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] parts = text.split(PARAGRAPH_SEP, -1);
        for (String part : parts) {
            if (!part.isEmpty()) {
                paragraphs.add(part);
            }
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

        // 合并短句；对超长单句使用字符级拆分
        StringBuilder buffer = new StringBuilder();//缓冲区内保留拼在一起的短句
        for (String sentence : sentences) {
            if (sentence.length() > maxChunkSize) {//还是太长的长句
                // 先刷新缓冲区，再处理超长句子
                if (buffer.length() > 0) {
                    results.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                recursiveCharSplit(sentence, results);
            } else if (buffer.length() + sentence.length() + 1 > maxChunkSize) {//再加一句就太长了，此时截止短句的加入
                // 缓冲区已满，先刷新再添加新句子
                results.add(buffer.toString().trim());
                buffer.setLength(0);
                buffer.append(sentence);
            } else {  // 将句子追加到缓冲区
                if (buffer.length() > 0) {
                    buffer.append(" ");
                }
                buffer.append(sentence);
            }
        }
        // 刷新剩余内容
        if (buffer.length() > 0) {
            results.add(buffer.toString().trim());
        }
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
