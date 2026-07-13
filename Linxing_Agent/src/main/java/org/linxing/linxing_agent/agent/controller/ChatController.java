package org.linxing.linxing_agent.agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.agent.adapter.SseChatAdapter;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.core.PendingClarificationRegistry;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.agent.service.IChatMessageService;
import org.linxing.linxing_agent.agent.service.IChatSessionService;
import org.linxing.linxing_agent.agent.service.impl.AgentStepServiceImpl;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;
import org.linxing.linxing_agent.agent.vo.ChatMessageVO;
import org.linxing.linxing_agent.agent.vo.ChatSessionVO;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final SseChatAdapter sseChatAdapter;
    private final IChatSessionService chatSessionService;
    private final IChatMessageService chatMessageService;
    private final AgentStepServiceImpl agentStepService;
    private final PendingClarificationRegistry clarificationRegistry;

    /**
     * SSE流式对话
     * @param request
     * @param httpResponse
     * @return
     */
    @PostMapping("/chat")
    public SseEmitter agentChat(@RequestBody ChatRequest request,
                                HttpServletResponse httpResponse) {
        httpResponse.setHeader("Cache-Control", "no-cache");//禁止缓存，确保SSE实时推送
        httpResponse.setHeader("X-Accel-Buffering", "no");//禁止Nginx缓冲

        request.setUserId(BaseContext.requireCurrentUserId());
        return sseChatAdapter.streamChat(request);
    }

    /**
     * HumanInTheLoop 澄清回复端点
     * <p>
     * 当 study_plan 工作流暂停等待用户澄清时，前端调用此端点提交用户回复，
     * 唤醒阻塞的 CompletableFuture，工作流继续执行。
     * @param body 包含 sessionId 和 answer
     * @return 操作结果
     */
    @PostMapping("/workflow/clarify")
    public Result<Map<String, Object>> submitClarification(@RequestBody Map<String, Object> body) {
        Object sessionIdObj = body.get("sessionId");
        String answer = (String) body.getOrDefault("answer", "");

        if (sessionIdObj == null) {
            return Result.error("sessionId 不能为空");
        }

        String clarificationId = String.valueOf(sessionIdObj);
        boolean completed = clarificationRegistry.complete(clarificationId, answer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completed", completed);
        if (!completed) {
            result.put("message", "未找到待处理的澄清请求，可能已超时或已回复");
        }

        log.info("[ChatController] 澄清回复 sessionId={}, completed={}", clarificationId, completed);
        return Result.success(result);
    }

    /**
     * 创建新对话会话
     * @param body
     * @return
     */
    @PostMapping("/sessions")
    public Result<ChatSessionVO> createSession(@RequestBody Map<String, String> body) {
        Integer userId = BaseContext.requireCurrentUserId();
        String title = body.getOrDefault("title", "新对话");
        return Result.success(chatSessionService.createSession(userId, title));
    }

    /**
     * 分页查询当前用户的对话会话列表
     * @param page
     * @param size
     * @return
     */
    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionVO>> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Integer userId = BaseContext.requireCurrentUserId();
        return Result.success(chatSessionService.listSessions(userId, page, size));
    }

    /**
     * 删除对话会话
     * @param sessionId
     * @return
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Integer sessionId) {
        chatSessionService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * 更新会话标题（手动重命名）
     * @param sessionId
     * @param body 包含 title
     * @return
     */
    @PutMapping("/sessions/{sessionId}/title")
    public Result<Void> updateTitle(@PathVariable Integer sessionId,
                                    @RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "新对话");
        chatSessionService.updateTitle(sessionId, title);
        return Result.success();
    }

    /**
     * AI 自动命名会话：基于首条用户消息 + 首条助手回答，调用 LLM 生成简短标题。
     * 仅当标题仍为默认占位（"新对话"）时才会真正生成，避免覆盖已命名的会话。
     * @param sessionId
     * @return 包含新生成的 title
     */
    @PostMapping("/sessions/{sessionId}/auto-title")
    public Result<Map<String, String>> autoTitle(@PathVariable Integer sessionId) {
        String title = chatSessionService.autoGenerateTitle(sessionId);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("title", title);
        return Result.success(result);
    }

    /**
     * 获取会话下的消息列表，优先读缓存，缓存不一致时回源DB并刷新缓存
     * @param sessionId
     * @return
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessageVO>> getMessages(@PathVariable Integer sessionId) {
        return Result.success(chatMessageService.getMessages(sessionId));
    }

    /**
     * 按消息ID懒加载该消息的agent推理步骤
     */
    @GetMapping("/messages/{messageId}/steps")
    public Result<List<AgentStepVO>> getMessageSteps(@PathVariable Integer messageId) {
        return Result.success(agentStepService.getStepsByMessageId(messageId));
    }

    /**
     * 删除消息及其所有子消息
     * @param messageId
     * @return
     */
    @DeleteMapping("/messages/{messageId}/subtree")
    public Result<Void> deleteSubtree(@PathVariable Integer messageId) {
        chatMessageService.deleteSubtree(messageId);
        return Result.success();
    }
}
