package org.linxing.linxing_agent.agent.memory.longterm.workspace;

/**
 * Memory Workspace 访问异常：路径越界、文件读写失败、用户根目录缺失等统一抛出。
 * <p>设计为 RuntimeException：Memory 读取异常在上层（Builder/Worker）统一降级为告警，不应中断主对话流程。
 */
public class MemoryAccessException extends RuntimeException {

    public MemoryAccessException(String message) {
        super(message);
    }

    public MemoryAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
