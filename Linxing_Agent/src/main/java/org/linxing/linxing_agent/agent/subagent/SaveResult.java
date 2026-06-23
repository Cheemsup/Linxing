package org.linxing.linxing_agent.agent.subagent;

/**
 * 保存工具返回结果的小对象，用于在 {@link SubAgentContext} 中传递保存后的 ID 与数量。
 */
public record SaveResult(int id, int count) {
}
