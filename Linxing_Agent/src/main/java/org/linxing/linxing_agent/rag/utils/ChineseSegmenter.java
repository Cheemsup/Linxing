package org.linxing.linxing_agent.rag.utils;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.util.List;

/**
 * 中文分词工具，基于 Jieba 分词器，用于 PostgreSQL 全文检索的 tsvector 预处理。
 */
public final class ChineseSegmenter {

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private ChineseSegmenter() {
    }

    /**
     * 对文本进行中文分词，用空格分隔各词条，供 PostgreSQL to_tsvector 索引使用。
     * 对中英文混合文本：英文/数字保持原样，中文按词切分。
     *
     * @param text 原始文本
     * @return 空格分隔的词条文本
     */
    public static String segment(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<SegToken> tokens = SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH);
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (SegToken token : tokens) {
            String word = token.word.trim();
            if (!word.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(word);
            }
        }
        return sb.toString();
    }
}
