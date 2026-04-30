package org.linxing.linxing_agent.constant;

/**
 * Chunk 类型常量，定义所有合法的 chunk_type 值。
 * 策略层和管线层统一使用此类避免字符串硬编码。
 */
public final class ChunkTypeConstants {

    public static final String CODE = "code";
    public static final String TABLE = "table";
    public static final String QA_PAIR = "qa_pair";
    public static final String SECTION = "section";
    public static final String CONTEXT_WEAK = "context_weak";
    public static final String GENERAL = "general";

    private ChunkTypeConstants() {
    }
}
