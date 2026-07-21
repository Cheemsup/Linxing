package org.linxing.linxing_agent.agent.memory.window;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.common.constant.MessageType;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Summary 独立持久化服务（thePlan P1-2：回答前主动判定 + 挂载式落盘）。
 * <p>
 * 取代旧体系"纯内存、不落库、被动在工具轮次后触发"的摘要方式（旧 {@code SummaryMemory}
 * 已于 2-B 删除）。本服务把 summary 作为 {@code type='summary'} 的普通 chat_messages 行落库，
 * 挂在路径末端作为新叶子，用户消息作为其子节点。Recovery 沿 parent_id 天然命中。
 * <p>
 * 落盘流程（thePlan P1-2 第 1~3 步，第 4 步用户消息挂载由调用方处理）：
 * <ol>
 *   <li>调用 {@code summaryModel}（非流式）压缩"上一个 summary 挂点（或 Root）到当前路径末端"的历史</li>
 *   <li>插入 {@code type='summary'} 行，{@code parent_id = 当前路径末端 message_id}</li>
 *   <li>刷新路径后续新消息的 {@code nearest_summary_message_id} 指向新 summary（被压缩旧消息不动）</li>
 * </ol>
 * 注意：本类中的 {@code ChatMessage} 指 langchain4j 消息，实体用全限定名 {@code org.linxing.linxing_agent.agent.entity.ChatMessage}。
 */
@Slf4j
@Service
public class SummaryService {

    /** Summary 维护提示词（thePlan 第五节初稿，待调）。 */
    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是对话压缩器，工作是辅助完成上下文的压缩。给定你需要压缩的内容，产出一段覆盖事实、决策、工具结论、未决问题的摘要。" +
            "要求：保留所有 tool 执行的关键结论（而非过程）、保留用户明确偏好、保留待办。";

    private final ChatMessageMapper chatMessageMapper;
    private final OpenAiChatModel summaryModel;
    private final IRuntimeMirrorService runtimeMirrorService; // P3 Mirror：summary 行作为普通 message 落库后镜像到 mirror:msgs

    public SummaryService(LlmManager llmManager, ChatMessageMapper chatMessageMapper,
                          IRuntimeMirrorService runtimeMirrorService) {
        this.chatMessageMapper = chatMessageMapper;
        this.summaryModel = llmManager.getModel(LlmType.SUMMARY_MODEL);
        this.runtimeMirrorService = runtimeMirrorService;
    }

    /**
     * 生成并持久化 Summary，挂在路径末端。
     *
     * @param userId             用户 id
     * @param sessionId          会话 id
     * @param pathEndMessageId   当前路径末端 message id（summary 的 parent）
     * @param toSummarizeMessages 待压缩的历史 langchain4j 消息（从上一个 summary 挂点或 Root 到路径末端）
     * @return 落盘后的 summary 实体；失败返回 null，调用方应降级为不压缩
     */
    public ChatMessage summarizeAndPersist(Integer userId, Integer sessionId,
                                           Integer pathEndMessageId,
                                           List<dev.langchain4j.data.message.ChatMessage> toSummarizeMessages) {
        if (toSummarizeMessages == null || toSummarizeMessages.isEmpty()) {
            return null;
        }
        String dialogText = buildDialogText(toSummarizeMessages);
        String prompt = SUMMARY_SYSTEM_PROMPT + "\n\n以下是需要压缩的内容：\n" + dialogText;

        String summaryText;
        try {
            summaryText = summaryModel.chat(prompt);//发送到模型，执行压缩
        } catch (Exception e) {
            log.warn("[SummaryService] summary 生成失败，降级为不压缩: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
        if (summaryText == null || summaryText.isBlank()) {
            return null;
        }

        // 插入 summary 行：type=MessageType.SUMMARY，parent_id 指向路径末端，作为路径新叶子
        ChatMessage summaryMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(pathEndMessageId)
                .type(MessageType.SUMMARY)
                .content(summaryText)
                .sources("[]")
                .nearestSummaryMessageId(null)
                .createdAt(OffsetDateTime.now())
                .build();
        chatMessageMapper.insert(summaryMsg);
        // redis-Mirror：summary 作为 type='summary' 的普通 message 进 mirror:msgs（Recovery 会沿 parentId 命中）
        runtimeMirrorService.appendMessage(sessionId, summaryMsg);

        log.info("[SummaryService] summary 落盘: sessionId={}, summaryId={}, parentId={}",
                sessionId, summaryMsg.getId(), pathEndMessageId);
        return summaryMsg;
    }

    /**
     * 点查某消息回溯路径上最近的 summary 节点（thePlan P1-3 Recovery 加速）。
     * @return 命中的 summary 实体；未填值或指向不存在返回 null
     */
    public ChatMessage findNearestSummary(Integer messageId) {
        Integer summaryId = chatMessageMapper.selectNearestSummaryId(messageId);
        if (summaryId == null) {
            return null;
        }
        return chatMessageMapper.selectById(summaryId);
    }

    /**
     * 把 langchain4j 消息列表拼成供压缩模型消费的对话文本。
     */
    private String buildDialogText(List<dev.langchain4j.data.message.ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (dev.langchain4j.data.message.ChatMessage msg : messages) {
            String text = textOf(msg);
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(prefixOf(msg)).append(text).append("\n");
        }
        return sb.toString();
    }

    private String prefixOf(dev.langchain4j.data.message.ChatMessage msg) {
        if (msg instanceof UserMessage) return "用户：";
        if (msg instanceof AiMessage) return "助手：";
        if (msg instanceof ToolExecutionResultMessage) return "工具结果：";
        return "";
    }

    private String textOf(dev.langchain4j.data.message.ChatMessage msg) {
        if (msg instanceof UserMessage um) return um.singleText();
        if (msg instanceof AiMessage am) return am.text();
        if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) return sm.text();
        if (msg instanceof ToolExecutionResultMessage tm) return tm.text();
        return null;
    }
}
