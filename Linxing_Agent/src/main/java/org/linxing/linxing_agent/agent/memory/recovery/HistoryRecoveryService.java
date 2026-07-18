package org.linxing.linxing_agent.agent.memory.recovery;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.agent.memory.SummaryService;
import org.linxing.linxing_agent.agent.memory.TokenEstimator;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史 Recovery 服务（thePlan P1-3，2-C 从 {@code ChatMessageServiceImpl} 下沉至本包）。
 * <p>
 * 职责：从当前用户消息沿 parentId 回溯，重建含 tool 调用/结果的历史，供 AgentMemory 装载。
 * <p>
 * summary 点查统一经 {@link SummaryService#findNearestSummary(Integer)}（2-C 起启用，
 * 消化原 L4 预留入口），不再在 Recovery 内直查 mapper。
 * <p>
 * 注意：本类的 {@code ChatMessage} 指 langchain4j 消息，实体用全限定名
 * {@code org.linxing.linxing_agent.agent.entity.ChatMessage}，二者不冲突。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryRecoveryService {

    private static final int MAX_HISTORY_ROUNDS = 10;

    private final ChatMessageMapper chatMessageMapper;
    private final AgentStepMapper agentStepMapper;
    private final TokenEstimator tokenEstimator;
    private final SummaryService summaryService;
    private final IRuntimeMirrorService runtimeMirrorService; // P3 Mirror：mirror-first 读源，miss/异常退化到 DB

    /**
     * Recovery（thePlan P1-3 + P3 Mirror 读路径）：从当前用户消息沿 parentId 回溯，重建含 tool 调用/结果的历史。
     * <p>
     * P3 起改为 mirror-first：
     * <ol>
     *   <li>入口 PK 查 {@code selectById(currentUserMsgId)}（1 次，不可避免）</li>
     *   <li>从 Mirror 读 {@code mirror:msgs}/{@code mirror:steps} 全 session 两 Hash；两者皆命中 →
     *       内存按 parentId 链回溯（O(1)/hop）+ 内存 tool 配对，产出同形 {@link RecoveredHistory}</li>
     *   <li>任一 Hash miss / 数量对不上 / 异常 → 退化到 DB 路径 {@link #recoverFromDb}，
     *       成功后 {@code replaceAll} 热身 Mirror（cache-aside，热身失败无碍）</li>
     * </ol>
     * 降级契约：任意 Redis 异常 → DB Recovery，正确性不受影响（nowRefact §4.4）。
     *
     * @param currentUserMsgId 当前用户消息 id
     * @param tokenBudget      历史 token 预算（超则从旧端截断）；&lt;=0 表示不限制
     * @return Recovery 结果；当前消息不存在或无历史返回空结果
     */
    public RecoveredHistory recoverHistory(Integer currentUserMsgId, long tokenBudget) {
        org.linxing.linxing_agent.agent.entity.ChatMessage currentMsg = chatMessageMapper.selectById(currentUserMsgId);
        if (currentMsg == null) {
            return RecoveredHistory.builder()
                    .messages(List.of()).pathEntities(List.of())
                    .summaryEntity(null).pathEndMessageId(null)
                    .turnBoundaries(List.of()).build();
        }

        // 1. mirror-first：两 Hash 皆命中则内存回溯
        try {
            List<org.linxing.linxing_agent.agent.entity.ChatMessage> allMsgs =
                    runtimeMirrorService.loadMessages(currentMsg.getSessionId());
            List<AgentStep> allSteps = runtimeMirrorService.loadSteps(currentMsg.getSessionId());
            if (allMsgs != null && allSteps != null) {
                log.debug("[Recovery] using mirror: sessionId={}, msgCount={}, stepCount={}",
                        currentMsg.getSessionId(), allMsgs.size(), allSteps.size());
                return recoverFromMirror(currentMsg, allMsgs, allSteps, tokenBudget);
            }
        } catch (Exception e) {
            log.warn("[Recovery] mirror 读失败, 退化到 DB: sessionId={}, error={}",
                    currentMsg.getSessionId(), e.getMessage());
        }

        // 2. DB 路径 → cache-aside 热身 Mirror
        RecoveredHistory dbResult = recoverFromDb(currentMsg, tokenBudget);
        if (dbResult.getPathEndMessageId() != null && !dbResult.getPathEntities().isEmpty()) {
            try {
                List<AgentStep> sessionSteps = agentStepMapper.selectBySessionId(currentMsg.getSessionId());
                runtimeMirrorService.replaceAll(currentMsg.getSessionId(),
                        dbResult.getPathEntities(), sessionSteps);
                log.debug("[Recovery] mirror cache-aside 热身: sessionId={}, msgCount={}, stepCount={}",
                        currentMsg.getSessionId(), dbResult.getPathEntities().size(),
                        sessionSteps != null ? sessionSteps.size() : 0);
            } catch (Exception e) {
                log.warn("[Recovery] mirror 热身失败（无碍，下次读再退化）: sessionId={}, error={}",
                        currentMsg.getSessionId(), e.getMessage());
            }
        }
        return dbResult;
    }

    /**
     * DB 路径 Recovery（原 P1-3 逻辑，2-C 下沉自 ChatMessageServiceImpl，P3 抽取为独立方法）。
     * <p>
     * 流程：沿 parentId 回溯路径链 → SummaryService 点查 nearest_summary 截断 → token 预算截断 →
     * 逐条重建 langchain4j 消息（assistant 带 tool 时按 step_data.tool_call_id 配对回放）。
     */
    private RecoveredHistory recoverFromDb(org.linxing.linxing_agent.agent.entity.ChatMessage currentMsg, long tokenBudget) {
        // 1. 沿 parentId 回溯路径实体链（从旧到新）
        List<org.linxing.linxing_agent.agent.entity.ChatMessage> chain = new ArrayList<>();
        Integer parentId = currentMsg.getParentId();
        while (parentId != null) {
            org.linxing.linxing_agent.agent.entity.ChatMessage parentMsg = chatMessageMapper.selectById(parentId);
            if (parentMsg == null) {
                break;
            }
            chain.add(parentMsg);
            parentId = parentMsg.getParentId();
        }
        Collections.reverse(chain);
        if (chain.isEmpty()) {
            return RecoveredHistory.builder()
                    .messages(List.of()).pathEntities(List.of())
                    .summaryEntity(null).pathEndMessageId(null)
                    .turnBoundaries(List.of()).build();
        }
        Integer pathEndMessageId = chain.get(chain.size() - 1).getId();

        // 2. 经 SummaryService 统一入口点查 nearest_summary：命中则截断到 summary 之后（被压缩旧消息丢弃）
        org.linxing.linxing_agent.agent.entity.ChatMessage summaryEntity = summaryService.findNearestSummary(currentMsg.getId());
        Integer summaryId = summaryEntity != null ? summaryEntity.getId() : null;
        List<org.linxing.linxing_agent.agent.entity.ChatMessage> effective;
        if (summaryEntity != null && summaryId != null) {
            // 路径中定位 summary，保留 summary 及其之后的消息
            int idx = -1;
            for (int i = 0; i < chain.size(); i++) {
                if (summaryId.equals(chain.get(i).getId())) {
                    idx = i;
                    break;
                }
            }
            effective = idx >= 0 ? new ArrayList<>(chain.subList(idx, chain.size())) : new ArrayList<>(chain);
        } else {
            effective = new ArrayList<>(chain);
        }

        // 3. token 预算截断：从旧端丢弃，直到累积 token 落入预算（summary 命中时保留 summary 不丢）
        if (tokenBudget > 0) {
            long acc = 0;
            int cutFrom = 0;
            // 从新到旧累加，定位可保留的最旧索引
            for (int i = effective.size() - 1; i >= 0; i--) {
                long t = tokenEstimator.estimate(toLangchainMessages(effective.get(i)));
                if (acc + t > tokenBudget) {
                    cutFrom = i + 1;
                    break;
                }
                acc += t;
            }
            if (summaryEntity != null) {
                // summary 必须保留：cutFrom 不得越过 summary 在 effective 中的位置（index 0）
                cutFrom = Math.max(cutFrom, 1);
            }
            if (cutFrom > 0 && cutFrom < effective.size()) {
                effective = new ArrayList<>(effective.subList(cutFrom, effective.size()));
            }
        }

        // 4. 逐条重建 langchain4j 消息（assistant 带 tool 时展开为 AiMessage + ToolExecutionResultMessage 序列）
        //    同步产出 TurnBoundary：每遇到 user/summary 起始消息开一个新 Turn，区间左闭右开。
        List<ChatMessage> messages = new ArrayList<>();
        List<TurnBoundary> turnBoundaries = new ArrayList<>();
        Integer currentTurnStartId = null;
        int currentTurnStartIdx = 0;
        for (org.linxing.linxing_agent.agent.entity.ChatMessage msg : effective) {
            String type = msg.getType();
            boolean isTurnStart = "user".equals(type) || "summary".equals(type);
            if (isTurnStart) {
                // 上一 Turn 收尾
                if (currentTurnStartId != null) {
                    turnBoundaries.add(TurnBoundary.builder()
                            .turnStartMessageId(currentTurnStartId)
                            .startIdx(currentTurnStartIdx)
                            .endIdx(messages.size())
                            .build());
                }
                currentTurnStartId = msg.getId();
                currentTurnStartIdx = messages.size();
            }
            messages.addAll(toLangchainMessages(msg));
        }
        // 最后一个 Turn 收尾
        if (currentTurnStartId != null) {
            turnBoundaries.add(TurnBoundary.builder()
                    .turnStartMessageId(currentTurnStartId)
                    .startIdx(currentTurnStartIdx)
                    .endIdx(messages.size())
                    .build());
        }

        return RecoveredHistory.builder()
                .messages(messages)
                .pathEntities(chain)
                .summaryEntity(summaryEntity)
                .pathEndMessageId(pathEndMessageId)
                .turnBoundaries(turnBoundaries)
                .build();
    }

    /**
     * Mirror 路径 Recovery（thePlan P3）：基于全 session 两 Hash 内存回溯，不查 DB（除入口 PK）。
     * <p>
     * 与 {@link #recoverFromDb} 同构产出 {@link RecoveredHistory}，保证对调用方透明：
     * <ul>
     *   <li>内存按 parentId 链回溯（HashMap O(1)/hop），替代 DB 逐跳 selectById</li>
     *   <li>Summary：直接读 currentMsg.nearestSummaryMessageId（Mirror message 对象已含此字段），
     *       无需 DB 点查 SummaryService.findNearestSummary</li>
     *   <li>step 配对：内存按 chatMessageId 分组后调 {@link #toLangchainMessages} 重载</li>
     * </ul>
     */
    private RecoveredHistory recoverFromMirror(org.linxing.linxing_agent.agent.entity.ChatMessage currentMsg,
                                               List<org.linxing.linxing_agent.agent.entity.ChatMessage> allMsgs,
                                               List<AgentStep> allSteps, long tokenBudget) {
        // 1. 建 msgId → entity 索引，内存沿 parentId 回溯
        Map<Integer, org.linxing.linxing_agent.agent.entity.ChatMessage> byId = new HashMap<>(allMsgs.size());
        for (org.linxing.linxing_agent.agent.entity.ChatMessage m : allMsgs) {
            if (m != null && m.getId() != null) {
                byId.put(m.getId(), m);
            }
        }
        List<org.linxing.linxing_agent.agent.entity.ChatMessage> chain = new ArrayList<>();
        Integer parentId = currentMsg.getParentId();
        while (parentId != null) {
            org.linxing.linxing_agent.agent.entity.ChatMessage parentMsg = byId.get(parentId);
            if (parentMsg == null) {
                break; // 镜像缺祖先 → 截断（理论上 cache-aside 已保证完整；缺则交给 DB 路径）
            }
            chain.add(parentMsg);
            parentId = parentMsg.getParentId();
        }
        Collections.reverse(chain);
        if (chain.isEmpty()) {
            return RecoveredHistory.builder()
                    .messages(List.of()).pathEntities(List.of())
                    .summaryEntity(null).pathEndMessageId(null)
                    .turnBoundaries(List.of()).build();
        }
        Integer pathEndMessageId = chain.get(chain.size() - 1).getId();

        // 2. Summary：直接读 currentMsg.nearestSummaryMessageId（"之前"语义），在 chain 中定位截断
        Integer summaryId = currentMsg.getNearestSummaryMessageId();
        org.linxing.linxing_agent.agent.entity.ChatMessage summaryEntity =
                summaryId != null ? byId.get(summaryId) : null;
        List<org.linxing.linxing_agent.agent.entity.ChatMessage> effective;
        if (summaryEntity != null && summaryId != null) {
            int idx = -1;
            for (int i = 0; i < chain.size(); i++) {
                if (summaryId.equals(chain.get(i).getId())) {
                    idx = i;
                    break;
                }
            }
            effective = idx >= 0 ? new ArrayList<>(chain.subList(idx, chain.size())) : new ArrayList<>(chain);
        } else {
            effective = new ArrayList<>(chain);
        }

        // 3. step 按 chatMessageId 分组（内存，替代 DB selectByChatMessageId）
        Map<Integer, List<AgentStep>> stepsByMsgId = new HashMap<>();
        if (allSteps != null) {
            for (AgentStep s : allSteps) {
                if (s == null || s.getChatMessageId() == null) continue;
                stepsByMsgId.computeIfAbsent(s.getChatMessageId(), k -> new ArrayList<>()).add(s);
            }
        }

        // 4. token 预算截断（与 DB 路径同逻辑）
        if (tokenBudget > 0) {
            long acc = 0;
            int cutFrom = 0;
            for (int i = effective.size() - 1; i >= 0; i--) {
                org.linxing.linxing_agent.agent.entity.ChatMessage m = effective.get(i);
                List<AgentStep> stepsForMsg = stepsByMsgId.getOrDefault(m.getId(), List.of());
                long t = tokenEstimator.estimate(toLangchainMessages(m, stepsForMsg));
                if (acc + t > tokenBudget) {
                    cutFrom = i + 1;
                    break;
                }
                acc += t;
            }
            if (summaryEntity != null) {
                cutFrom = Math.max(cutFrom, 1);
            }
            if (cutFrom > 0 && cutFrom < effective.size()) {
                effective = new ArrayList<>(effective.subList(cutFrom, effective.size()));
            }
        }

        // 5. 逐条重建 + TurnBoundary（与 DB 路径同逻辑，step 走内存分组）
        List<ChatMessage> messages = new ArrayList<>();
        List<TurnBoundary> turnBoundaries = new ArrayList<>();
        Integer currentTurnStartId = null;
        int currentTurnStartIdx = 0;
        for (org.linxing.linxing_agent.agent.entity.ChatMessage msg : effective) {
            String type = msg.getType();
            boolean isTurnStart = "user".equals(type) || "summary".equals(type);
            if (isTurnStart) {
                if (currentTurnStartId != null) {
                    turnBoundaries.add(TurnBoundary.builder()
                            .turnStartMessageId(currentTurnStartId)
                            .startIdx(currentTurnStartIdx)
                            .endIdx(messages.size())
                            .build());
                }
                currentTurnStartId = msg.getId();
                currentTurnStartIdx = messages.size();
            }
            messages.addAll(toLangchainMessages(msg, stepsByMsgId.getOrDefault(msg.getId(), List.of())));
        }
        if (currentTurnStartId != null) {
            turnBoundaries.add(TurnBoundary.builder()
                    .turnStartMessageId(currentTurnStartId)
                    .startIdx(currentTurnStartIdx)
                    .endIdx(messages.size())
                    .build());
        }

        return RecoveredHistory.builder()
                .messages(messages)
                .pathEntities(chain)
                .summaryEntity(summaryEntity)
                .pathEndMessageId(pathEndMessageId)
                .turnBoundaries(turnBoundaries)
                .build();
    }

    /**
     * 把一条 chat_messages 实体重建为 langchain4j 消息列表（DB 路径：按 msgId 现 selectByChatMessageId 取 step）。
     * <p>对 assistant 消息：若其关联的 agent_steps 含 tool_call 行，重建为
     * {@code AiMessage(toolExecutionRequests)} + 紧跟的 {@code ToolExecutionResultMessage}（按 tool_call_id 配对）；
     * 否则重建为纯文本 {@code AiMessage}。summary 节点重建为 UserMessage（前缀"【对话历史摘要】"）。
     */
    private List<ChatMessage> toLangchainMessages(org.linxing.linxing_agent.agent.entity.ChatMessage msg) {
        return toLangchainMessages(msg, agentStepMapper.selectByChatMessageId(msg.getId()));
    }

    /**
     * 把一条 chat_messages 实体重建为 langchain4j 消息列表（Mirror 路径：step 由调用方传入，避免逐条 DB 查询）。
     * <p>配对逻辑与 {@link #toLangchainMessages(org.linxing.linxing_agent.agent.entity.ChatMessage)} 完全一致，
     * 仅 step 来源不同（内存分组 vs DB selectByChatMessageId）。
     *
     * @param steps 该 msg 关联的 agent_steps（可 null，等价于无 tool 调用）
     */
    private List<ChatMessage> toLangchainMessages(org.linxing.linxing_agent.agent.entity.ChatMessage msg, List<AgentStep> steps) {
        String type = msg.getType();
        if ("user".equals(type)) {
            return List.of(UserMessage.from(msg.getContent()));
        }
        if ("summary".equals(type)) {
            return List.of(UserMessage.from("【对话历史摘要】\n" + msg.getContent()));
        }
        // assistant
        List<ToolExecutionRequest> toolReqs = new ArrayList<>();
        Map<String, String> resultById = new HashMap<>();
        if (steps != null) {
            for (AgentStep s : steps) {
                Map<String, Object> data = s.getStepData();
                if (data == null) continue;
                Object tcId = data.get("tool_call_id");
                if (tcId == null) continue;
                String callId = String.valueOf(tcId);
                if ("tool_call".equals(s.getStepType())) {
                    Object name = data.get("tool_name");
                    String args = s.getContent() != null ? s.getContent()
                            : (data.get("arguments") != null ? String.valueOf(data.get("arguments")) : "");
                    toolReqs.add(ToolExecutionRequest.builder()
                            .id(callId)
                            .name(name != null ? String.valueOf(name) : "unknown")
                            .arguments(args != null ? args : "")
                            .build());
                } else if ("tool_result".equals(s.getStepType())) {
                    String text = s.getContent() != null ? s.getContent() : "";
                    resultById.put(callId, text);
                }
            }
        }
        List<ChatMessage> out = new ArrayList<>();
        if (!toolReqs.isEmpty()) {
            AiMessage ai = AiMessage.from(msg.getContent() != null ? msg.getContent() : "", toolReqs);
            out.add(ai);
            // 按请求顺序追加对应的工具结果（结果缺失时跳过，避免配对错乱）
            for (ToolExecutionRequest req : toolReqs) {
                String result = resultById.get(req.id());
                if (result != null) {
                    out.add(ToolExecutionResultMessage.from(req, result));
                }
            }
            return out;
        }
        out.add(AiMessage.from(msg.getContent() != null ? msg.getContent() : ""));
        return out;
    }

    /**
     * 加载会话中的最近消息作为 Recovery 兜底（旧路径，不含 tool 回放，仅回放文本）。
     * {@code ChatServiceImpl.chat} 在 Recovery 返回空且 parent 非空时降级使用。
     */
    public List<org.linxing.linxing_agent.agent.entity.ChatMessage> loadRecentMessages(Integer sessionId) {
        List<org.linxing.linxing_agent.agent.entity.ChatMessage> allMessages = chatMessageMapper.selectBySessionId(sessionId);
        if (allMessages.isEmpty()) {
            return List.of();
        }
        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (allMessages.size() > maxMessages) {
            return allMessages.subList(allMessages.size() - maxMessages, allMessages.size());
        }
        return allMessages;
    }
}
