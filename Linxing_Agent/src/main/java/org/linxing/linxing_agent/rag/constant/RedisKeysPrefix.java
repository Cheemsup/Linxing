package org.linxing.linxing_agent.rag.constant;

public final class RedisKeysPrefix {

    public static final String DOC_PREVIEW = "doc_preview:";

    /** @deprecated P3 Runtime Mirror 落地后停写，靠 TTL 自然过期；前端与 Builder 统一迁到 {@link #MIRROR_MSGS} */
    @Deprecated
    public static final String SESSION_MSGS = "session:msgs:";

    /** @deprecated P3 Runtime Mirror 落地后停写，靠 TTL 自然过期；前端与 Builder 统一迁到 {@link #MIRROR_STEPS} */
    @Deprecated
    public static final String AGENT_STEPS = "agent:steps:";

    // —— P3 Runtime Mirror（session 粒度双 Hash，Builder 与前端共用）——

    /** mirror:msgs:{sessionId} Hash：field=msgId，value=ChatMessage 实体 JSON（含 nearestSummaryMessageId/parentId/type） */
    public static final String MIRROR_MSGS = "mirror:msgs:";

    /** mirror:steps:{sessionId} Hash：field=stepId，value=AgentStep 实体 JSON（含 chatMessageId/stepOrder/stepData） */
    public static final String MIRROR_STEPS = "mirror:steps:";

    /**
     * chat:response:{requestId} String：value=ChatResponse JSON。
     * <p>SSE reset 幂等键场景——前端 retry 复用同一 requestId 时，后端命中此缓存则直接复用已完成的
     * ChatResponse 推给新 emitter，不重跑推理、不重复落库（plan/exam/message 等）。
     * TTL 略大于 SSE 超时（30 分钟），默认 35 分钟，覆盖空闲 reset 窗口。
     */
    public static final String CHAT_RESPONSE = "chat:response:";

    private RedisKeysPrefix() {
    }
}
