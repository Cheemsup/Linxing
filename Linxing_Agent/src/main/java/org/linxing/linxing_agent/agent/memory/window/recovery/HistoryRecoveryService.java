package org.linxing.linxing_agent.agent.memory.window.recovery;

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
import org.linxing.linxing_agent.agent.memory.window.SummaryService;
import org.linxing.linxing_agent.agent.memory.TokenEstimator;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.common.constant.MessageType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史 Recovery 服务（thePlan P1-3，2-C 从 {@code ChatMessageServiceImpl} 下沉至本包）。
 * 职责：从当前用户消息沿 parentId 回溯，重建含 tool 调用/结果的历史，供 AgentMemory 装载。
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
    private final IRuntimeMirrorService runtimeMirrorService; // redis-mirror-first 读源，miss/异常退化到 DB

    /**
     * Recovery：以锚点消息为起点沿 parentId 回溯（含锚点自身），重建含 tool 调用/结果的历史。
     * redis镜像数据源查询消息/tool调用记录并组装返回
     * 任意 Redis 异常 → DB Recovery，正确性不受影响
     *
     * <p>锚点 = request.parentMessageId（上一条已落盘消息，通常是上轮 assistant 回答）
     *
     * <p>激活路径经 summary 截断后已是"summary + 后续节点全部内容"的最简形态
     * @param anchorMessageId 锚点消息 id（上一条已落盘消息）；null 表示无历史（首次对话）
     * @param sessionId       会话 id（anchorMessageId 为 null 时用，用于空结果上下文）
     * @return Recovery 结果；锚点不存在或无历史返回空结果
     *
     * //TODO：此处包含取出redis全部“消息+steps”的步骤，应该而言满足“前端全量复原+builder上下文构建”两条消费链路而不是分开取两次。需要考虑结合二者（构建一个内容保留和共享？）
     */
    public RecoveredHistory recoverHistory(Integer anchorMessageId, Integer sessionId) {
        // 锚点为 null（首次对话）→ 无历史可回溯
        if (anchorMessageId == null) {
            return RecoveredHistory.builder()
                    .messages(List.of())
                    .summaryEntity(null).pathEndMessageId(null)
                    .turnBoundaries(List.of()).build();
        }
        // 取出锚点消息作为回溯起点（含自身；提供 sessionId / parentId / nearestSummaryMessageId）
        org.linxing.linxing_agent.agent.entity.ChatMessage anchorMsg = chatMessageMapper.selectById(anchorMessageId);
        if (anchorMsg == null) {
            return RecoveredHistory.builder()
                    .messages(List.of())
                    .summaryEntity(null).pathEndMessageId(null)
                    .turnBoundaries(List.of()).build();
        }

        // reids-mirror-first：两 Hash 皆命中则内存回溯
        try {
            List<org.linxing.linxing_agent.agent.entity.ChatMessage> allMsgs =
                    runtimeMirrorService.loadMessages(anchorMsg.getSessionId());//从redis取出该session的全量消息
            List<AgentStep> allSteps = runtimeMirrorService.loadSteps(anchorMsg.getSessionId());//从redis取出该session的全量消息
            if (allMsgs != null && allSteps != null) {
                log.debug("[Recovery] using mirror: sessionId={}, msgCount={}, stepCount={}",
                        anchorMsg.getSessionId(), allMsgs.size(), allSteps.size());
                return recoverFromMirror(anchorMsg, allMsgs, allSteps);//构建激活路径的历史消息内容
            }
        } catch (Exception e) {
            log.warn("[Recovery] mirror 读失败, 退化到 DB: sessionId={}, error={}",
                    anchorMsg.getSessionId(), e.getMessage());
        }

        // redis镜像失效，DB 兜底，同时cache-aside 热身 Mirror
        RecoveredHistory dbResult = recoverFromDb(anchorMsg);
        if (dbResult.getPathEndMessageId() != null) {
            try {
                List<AgentStep> sessionSteps = agentStepMapper.selectBySessionId(anchorMsg.getSessionId());
                // mirror 语义对称说明：mirror:msgs 与 mirror:steps 均写全 session。
                List<org.linxing.linxing_agent.agent.entity.ChatMessage> sessionMsgs =
                        chatMessageMapper.selectBySessionId(anchorMsg.getSessionId());
                runtimeMirrorService.replaceAll(anchorMsg.getSessionId(),
                        sessionMsgs, sessionSteps);
                log.debug("[Recovery] mirror cache-aside 热身(全session): sessionId={}, msgCount={}, stepCount={}",
                        anchorMsg.getSessionId(),
                        sessionMsgs != null ? sessionMsgs.size() : 0,
                        sessionSteps != null ? sessionSteps.size() : 0);
            } catch (Exception e) {
                log.warn("[Recovery] mirror 热身失败（无碍，下次读再退化）: sessionId={}, error={}",
                        anchorMsg.getSessionId(), e.getMessage());
            }
        }
        return dbResult;
    }

    /**
     * DB 路径 Recovery
     * 沿 parentId 回溯路径链（含锚点自身）→ SummaryService 点查 nearest_summary 截断 →逐条重建 langchain4j 消息（assistant 带 tool 时按 step_data.tool_call_id 配对回放）。
     *
     * <p>锚点（currentMsg）自身纳入 chain 末端，因其是上一条已落盘消息（通常是上轮 assistant
     * 回答），必须出现在历史中。pathEndMessageId = chain 末端 = 锚点自身 = summary 的挂载点。
     */
    private RecoveredHistory recoverFromDb(org.linxing.linxing_agent.agent.entity.ChatMessage currentMsg) {
        // 沿 parentId 回溯路径实体链（从旧到新），锚点自身作为最新端纳入
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
        chain.add(currentMsg);//锚点自身入链（最新端）
        Integer pathEndMessageId = chain.get(chain.size() - 1).getId();

        // 经 SummaryService 统一入口点查 nearest_summary：命中则截断到 summary 之后（被压缩旧消息丢弃）
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

        // 逐条重建 langchain4j 消息（assistant 带 tool 时展开为 AiMessage + ToolExecutionResultMessage 序列）
        //    同步产出 TurnBoundary：每遇到 user/summary 起始消息开一个新 Turn，区间左闭右开。
        List<ChatMessage> messages = new ArrayList<>();
        List<TurnBoundary> turnBoundaries = new ArrayList<>();
        Integer currentTurnStartId = null;
        int currentTurnStartIdx = 0;
        for (org.linxing.linxing_agent.agent.entity.ChatMessage msg : effective) {
            String type = msg.getType();
            boolean isTurnStart = MessageType.USER.equals(type) || MessageType.SUMMARY.equals(type);
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
                .summaryEntity(summaryEntity)
                .pathEndMessageId(pathEndMessageId)
                .turnBoundaries(turnBoundaries)
                .build();
    }

    /**
     * Mirror 路径 Recovery：基于全 session 两 Hash 内存回溯，不查 DB
     *
     * 接受全量的redis内容，内部选择最终交由上游消费的链路、同时结合chat_messages以及agent_steps两表的映射消息并组装为langchain4j的格式
     *
     * <p>mirror:msgs 含全 session 消息（cache-aside 热身时 replaceAll 写全 session）；
     * 沿 parentId 链回溯激活路径，若某祖先缺失于 byId，回溯截断 → 退化到 DB 兜底。
     *
     * @param currentMsg
     * @param allMsgs
     * @param allSteps
     * @return
     */
    private RecoveredHistory recoverFromMirror(org.linxing.linxing_agent.agent.entity.ChatMessage currentMsg,
                                               List<org.linxing.linxing_agent.agent.entity.ChatMessage> allMsgs,
                                               List<AgentStep> allSteps) {
        //把全量 msgs 建成 msgId → entity 索引，后续沿 parentId 链 O(1) 回溯
        Map<Integer, org.linxing.linxing_agent.agent.entity.ChatMessage> byId = new HashMap<>(allMsgs.size());
        for (org.linxing.linxing_agent.agent.entity.ChatMessage m : allMsgs) {
            if (m != null && m.getId() != null) {
                byId.put(m.getId(), m);
            }
        }

        //沿 parentId 从锚点向上回溯激活路径链（含锚点自身），旁支消息不会命中 byId 故被自然排除
        List<org.linxing.linxing_agent.agent.entity.ChatMessage> chain = new ArrayList<>();
        Integer parentId = currentMsg.getParentId();
        while (parentId != null) {
            org.linxing.linxing_agent.agent.entity.ChatMessage parentMsg = byId.get(parentId);
            if (parentMsg == null) {
                break;//镜像缺祖先 → 截断，交给 DB 路径兜底
            }
            chain.add(parentMsg);
            parentId = parentMsg.getParentId();
        }
        Collections.reverse(chain);
        chain.add(currentMsg);//锚点自身入链（最新端；实体由调用方传入，不依赖 byId 命中）
        Integer pathEndMessageId = chain.get(chain.size() - 1).getId();

        //summary 截断——直接读 currentMsg.nearestSummaryMessageId 拿最近 summary 点，在 chain 中定位
        Integer summaryId = currentMsg.getNearestSummaryMessageId();
        org.linxing.linxing_agent.agent.entity.ChatMessage summaryEntity = summaryId != null ? byId.get(summaryId) : null;
        List<org.linxing.linxing_agent.agent.entity.ChatMessage> effective;
        if (summaryEntity != null && summaryId != null) {
            //在 chain 中找到 summary 的位置，保留 summary 及其之后的全部消息（summary 之前的旧消息已被压缩丢弃）
            int idx = -1;
            for (int i = 0; i < chain.size(); i++) {
                if (summaryId.equals(chain.get(i).getId())) {
                    idx = i;
                    break;
                }
            }
            effective = idx >= 0 ? new ArrayList<>(chain.subList(idx, chain.size())) : new ArrayList<>(chain);//通过之前的idx定位，现在进行List的截断
        } else {
            effective = new ArrayList<>(chain);//无summary节点，返回全量的chain提供给上下文builder消费
        }

        //steps 与 msgs 的结合准备——step 全量按 chatMessageId 分组，重建时按 msg 内存取
        Map<Integer, List<AgentStep>> stepsByMsgId = new HashMap<>();
        if (allSteps != null) {
            for (AgentStep s : allSteps) {
                if (s == null || s.getChatMessageId() == null) continue;
                stepsByMsgId.computeIfAbsent(s.getChatMessageId(), k -> new ArrayList<>()).add(s);
            }
        }

        //逐条重建 langchain4j 消息并产出 TurnBoundary——msgs 与 steps 在此融合
        List<ChatMessage> messages = new ArrayList<>();
        List<TurnBoundary> turnBoundaries = new ArrayList<>();
        Integer currentTurnStartId = null;
        int currentTurnStartIdx = 0;
        for (org.linxing.linxing_agent.agent.entity.ChatMessage msg : effective) {
            String type = msg.getType();
            boolean isTurnStart = MessageType.USER.equals(type) || MessageType.SUMMARY.equals(type);
            if (isTurnStart) {
                //遇到新 Turn 起点（user/summary），先把上一 Turn 收尾：记录其起点 msgId 与在 messages 中的左闭右开下标区间
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
            //取该 msg 关联的 steps，由 toLangchainMessages 把“1 实体 + N step”展开为 lc4j 交替序列后追加进扁平 messages（即是按照普通的文本流将消息与对应的steps记录结合起来）
            messages.addAll(toLangchainMessages(msg, stepsByMsgId.getOrDefault(msg.getId(), List.of())));
        }
        //最后一个 Turn 收尾（循环内只在遇到下一个起点时才记录，末尾 Turn 需补记）
        if (currentTurnStartId != null) {
            turnBoundaries.add(TurnBoundary.builder()
                    .turnStartMessageId(currentTurnStartId)
                    .startIdx(currentTurnStartIdx)
                    .endIdx(messages.size())
                    .build());
        }

        //messages=扁平lc4j序列(直接喂LLM)；
        //summaryEntity=命中的summary节点；pathEndMessageId=链头(最旧端)msgId；turnBoundaries=轮次刻度供Projection按轮决策
        return RecoveredHistory.builder()
                .messages(messages)
                .summaryEntity(summaryEntity)
                .pathEndMessageId(pathEndMessageId)
                .turnBoundaries(turnBoundaries)
                .build();
    }

    /**
     * 根据msgID从数据库查询对应的steps内容，然后二者组装为langchain4j的消息（平铺为文本流）
     *
     * @param msg
     * @return
     */
    private List<ChatMessage> toLangchainMessages(org.linxing.linxing_agent.agent.entity.ChatMessage msg) {
        return toLangchainMessages(msg, agentStepMapper.selectByChatMessageId(msg.getId()));
    }

    /**
     * 根据msgID从内存查询对应的steps内容，然后二者组装为langchain4j的消息（平铺为文本流）
     *
     * @param msg
     * @param steps 该 msg 关联的 agent_steps（可 null，等价于无 tool 调用）
     * @return
     */
    private List<ChatMessage> toLangchainMessages(org.linxing.linxing_agent.agent.entity.ChatMessage msg, List<AgentStep> steps) {
        String type = msg.getType();
        //user 消息直接转 UserMessage，不携带 steps
        if (MessageType.USER.equals(type)) {
            return List.of(UserMessage.from(msg.getContent()));
        }
        //summary 消息转 UserMessage，加前缀与真用户输入区分
        if (MessageType.SUMMARY.equals(type)) {
            return List.of(UserMessage.from("【对话历史摘要】\n" + msg.getContent()));
        }
        //assistant 消息：先把 steps 拆成“工具调用请求”与“工具结果”两组，按 tool_call_id 配对
        List<ToolExecutionRequest> toolReqs = new ArrayList<>();
        Map<String, String> resultById = new HashMap<>();
        if (steps != null) {
            for (AgentStep s : steps) {
                Map<String, Object> data = s.getStepData();
                if (data == null) continue;
                Object tcId = data.get("tool_call_id");//tool_call_id 是配对调用与结果的纽带
                if (tcId == null) continue;
                String callId = String.valueOf(tcId);
                if ("tool_call".equals(s.getStepType())) {
                    //tool_call 行：组装成 ToolExecutionRequest，塞进 AiMessage 的 toolExecutionRequests
                    Object name = data.get("tool_name");
                    String args = s.getContent() != null ? s.getContent()
                            : (data.get("arguments") != null ? String.valueOf(data.get("arguments")) : "");
                    toolReqs.add(ToolExecutionRequest.builder()
                            .id(callId)
                            .name(name != null ? String.valueOf(name) : "unknown")
                            .arguments(args != null ? args : "")
                            .build());
                } else if ("tool_result".equals(s.getStepType())) {
                    //tool_result 行：以 callId 为 key 存结果文本，待后面按请求顺序配对取出
                    String text = s.getContent() != null ? s.getContent() : "";
                    resultById.put(callId, text);
                }
            }
        }
        List<ChatMessage> out = new ArrayList<>();
        if (!toolReqs.isEmpty()) {
            //有工具调用：先一条带 toolExecutionRequests 的 AiMessage，再按请求顺序逐个追加配对结果
            AiMessage ai = AiMessage.from(msg.getContent() != null ? msg.getContent() : "", toolReqs);
            out.add(ai);
            //按请求顺序追加对应的工具结果。
            //0726 兜底：result 缺失时静默跳过会让 AiMessage(tool_calls) 后跟不全的 ToolExecutionResultMessage，
            //触发 OpenAI 协议硬错 "insufficient tool messages following tool_calls message"。
            //改为补占位符 ToolExecutionResultMessage，保证序列永远配对合法；同时告警定位缺失的 step。
            int missing = 0;
            for (ToolExecutionRequest req : toolReqs) {
                String result = resultById.get(req.id());
                if (result == null) {
                    missing++;
                    log.warn("[Recovery] tool_result 缺失，补占位符: msgId={}, toolCallId={}, tool={}",
                            msg.getId(), req.id(), req.name());
                    result = "[此工具结果已丢失：toolCallId=" + req.id()
                            + ", tool=" + req.name()
                            + "，DB/Mirror 中未找到对应 tool_result step]";
                }
                out.add(ToolExecutionResultMessage.from(req, result));
            }
            //L2 自检：配对不齐时告警（已补占位，序列合法，但提示数据源有问题需排查）
            if (missing > 0) {
                log.warn("[Recovery] tool_call/tool_result 配对不齐: msgId={}, toolCalls={}, missing={}, "
                                + "请排查 StepRecorder 持久化或 Mirror 即时写是否丢 step",
                        msg.getId(), toolReqs.size(), missing);
            }
            return out;
        }
        //无工具调用：纯文本 AiMessage
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
