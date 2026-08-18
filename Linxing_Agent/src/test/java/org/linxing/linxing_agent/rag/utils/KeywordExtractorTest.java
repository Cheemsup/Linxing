package org.linxing.linxing_agent.rag.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeywordExtractor} 单测：验证查询侧 tsquery 生成与索引侧（Jieba 分词）词条对齐——
 * 中文查询必须产出多字词（而非旧实现的单字），否则 PG '@@' 精确匹配 0 候选。
 * 期望分词输出以 jieba-analysis 1.0.2 实测为准。
 */
class KeywordExtractorTest {

    @Test
    @DisplayName("中文关键词：Jieba 多字词按 & 连接（与索引词条对齐）")
    void chineseQuery_shouldUseJiebaWordsWithAnd() {
        assertThat(KeywordExtractor.extractToTsquery("索引失效")).isEqualTo("索引 & 失效");
        assertThat(KeywordExtractor.extractToTsquery("索引失效的常见场景"))
                .isEqualTo("索引 & 失效 & 常见 & 场景");
    }

    @Test
    @DisplayName("英文关键词：Jieba 已转小写，停用词过滤后按 & 连接")
    void englishQuery_shouldLowercaseAndFilterStopwords() {
        assertThat(KeywordExtractor.extractToTsquery("What is RAG")).isEqualTo("rag");
        assertThat(KeywordExtractor.extractToTsquery("TCP三次握手和四次挥手"))
                .isEqualTo("tcp & 三次 & 握手 & 四次 & 挥手");
    }

    @Test
    @DisplayName("中英混排：数字/英文单独成词保留")
    void mixedQuery_shouldKeepEnglishAndNumericTokens() {
        assertThat(KeywordExtractor.extractToTsquery("Redis过期键的删除策略有哪些"))
                .isEqualTo("redis & 过期 & 键 & 删除 & 策略");
    }

    @Test
    @DisplayName("OR 变体：同一分词按 | 连接，供 AND 0 候选时放宽召回")
    void orQuery_shouldJoinWithPipe() {
        assertThat(KeywordExtractor.extractToTsqueryOr("索引失效")).isEqualTo("索引 | 失效");
        assertThat(KeywordExtractor.extractToTsqueryOr("索引失效的常见场景"))
                .isEqualTo("索引 | 失效 | 常见 | 场景");
    }

    @Test
    @DisplayName("口语/语篇停用词：过滤后贴近检索意图")
    void queryWithDiscourseWords_shouldFilterThem() {
        assertThat(KeywordExtractor.extractToTsquery("请告诉我索引失效的原因"))
                .isEqualTo("索引 & 失效 & 原因");
    }

    @Test
    @DisplayName("全标点/全停用词/空白/null → 空串")
    void meaninglessInput_shouldReturnEmpty() {
        assertThat(KeywordExtractor.extractToTsquery("。，，")).isEmpty();
        assertThat(KeywordExtractor.extractToTsquery("的 了 是")).isEmpty();
        assertThat(KeywordExtractor.extractToTsquery("   ")).isEmpty();
        assertThat(KeywordExtractor.extractToTsquery(null)).isEmpty();
    }

    @Test
    @DisplayName("重复关键词去重后只保留一次")
    void duplicateKeywords_shouldBeDeduplicated() {
        assertThat(KeywordExtractor.extractToTsquery("索引 索引 失效")).isEqualTo("索引 & 失效");
    }

    @Test
    @DisplayName("tsquery 特殊字符被清洗，防注入")
    void tsquerySpecialChars_shouldBeSanitized() {
        // 括号/引号等会被 sanitizeForTsquery 移除
        assertThat(KeywordExtractor.extractToTsquery("(索引优化)")).isEqualTo("索引 & 优化");
    }
}