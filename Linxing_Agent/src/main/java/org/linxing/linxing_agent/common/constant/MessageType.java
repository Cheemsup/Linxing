package org.linxing.linxing_agent.common.constant;

/**
 * chat_messages.type 字段的取值常量。
 *
 * <p>用于 {@code SummaryService} 落盘 summary 行、{@code HistoryRecoveryService} 重建
 * langchain4j 消息时按 type 分支，避免 "user"/"assistant"/"summary" 字面量散落。
 */
public final class MessageType {

    /** 用户消息。 */
    public static final String USER = "user";

    /** 助手消息。 */
    public static final String ASSISTANT = "assistant";

    /** 历史压缩摘要消息（type='summary' 的普通 chat_messages 行，挂在路径末端作新叶子）。 */
    public static final String SUMMARY = "summary";

    private MessageType() {
    }
}
