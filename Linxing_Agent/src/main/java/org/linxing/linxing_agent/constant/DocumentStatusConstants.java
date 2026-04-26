package org.linxing.linxing_agent.constant;

/**
 * 文档处理状态常量定义类
 * 定义文档导入和处理过程中的各种状态值
 */
public final class DocumentStatusConstants {

    /**
     * 处理中文档正在被切分和向量化
     */
    public static final String PROCESSING = "processing";

    /**
     * 文档处理已完成（成功状态）
     */
    public static final String COMPLETED = "completed";

    /**
     * 文档处理失败
     */
    public static final String FAILED = "failed";

}
