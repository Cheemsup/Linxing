package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.entity.ChatMessage;

import java.util.List;

/**
 * 运行时镜像服务（thePlan P3 / nowRefact §4.2~§4.4）。
 * <p>
 * Redis 作为 MySQL 的运行时镜像，session 粒度双 Hash：
 * <ul>
 *   <li>{@code mirror:msgs:{sessionId}} —— Hash，field=msgId，value={@link ChatMessage} 实体 JSON
 *       （含 {@code nearestSummaryMessageId}、{@code parentId}、{@code type}，供 Builder Recovery 内存回溯）</li>
 *   <li>{@code mirror:steps:{sessionId}} —— Hash，field=stepId，value={@link AgentStep} 实体 JSON
 *       （含 {@code chatMessageId}、{@code stepOrder}、{@code stepData}，保留 tool_call_id 配对语义）</li>
 * </ul>
 * 一次 {@code HGETALL} 各取全量后内存配对重建 {@code AiMessage(toolCalls)+ToolExecutionResultMessage}。
 * <p>
 * <b>降级契约</b>：所有方法 try-catch + 降级日志，绝不向上抛。读方法 miss/异常返回 {@code null}（由调用方
 * {@code HistoryRecoveryService} 退化到 DB Recovery，cache-aside 热身本镜像）。正确性不依赖 Redis。
 * <p>
 * <b>写幂等</b>：{@link #appendMessage}/{@link #appendStep} 以 id 为 Hash field，HPUT 天然幂等覆盖，
 * append/summary 落库/重挂补丁共用同一入口。每次写都 {@code expire} 续期（TTL 见 {@code rag.cache.mirror-ttl}）。
 */
public interface IRuntimeMirrorService {

    /**
     * HGETALL {@code mirror:msgs:{sessionId}}，按 createdAt 排序返回实体链。
     * @return 实体列表；Hash 不存在或任意异常返回 {@code null}（信号调用方退化到 DB）
     */
    List<ChatMessage> loadMessages(Integer sessionId);

    /**
     * HGETALL {@code mirror:steps:{sessionId}}，按 stepOrder、id 排序返回实体链。
     * @return 实体列表；Hash 不存在或任意异常返回 {@code null}
     */
    List<AgentStep> loadSteps(Integer sessionId);

    /**
     * HPUT 单条 message 字段 + expire 续期。幂等覆盖（append、summary 落库、重挂 parentId 补丁共用）。
     */
    void appendMessage(Integer sessionId, ChatMessage message);

    /**
     * HPUT 单条 step 字段 + expire 续期。幂等覆盖（StepRecorder 即时写、chatMessageId 回填后补丁共用）。
     */
    void appendStep(Integer sessionId, AgentStep step);

    /**
     * cache-aside 热身：DEL 两 Hash，putAll messages + putAll steps，expire 两 Hash。
     * 用于 DB 兜底成功后回填镜像。热身失败无碍（下次读再退化到 DB）。
     */
    void replaceAll(Integer sessionId, List<ChatMessage> messages, List<AgentStep> steps);

    /**
     * DEL 两 Hash（会话拆除/子树删除失效整 session 镜像，下次读重建）。
     */
    void deleteSession(Integer sessionId);
}
