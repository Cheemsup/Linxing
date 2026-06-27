package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.vo.ChatSessionVO;
import org.linxing.linxing_agent.common.result.PageResult;

public interface IChatSessionService {

    ChatSessionVO createSession(Integer userId, String title);

    PageResult<ChatSessionVO> listSessions(Integer userId, int page, int size);

    void deleteSession(Integer sessionId);

    void updateTitle(Integer sessionId, String title);

    /**
     * 基于 LLM 为会话自动生成标题：取首条用户消息 + 首条助手回答，调用默认 LLM 生成简短标题
     * @param sessionId 会话ID
     * @return 生成的新标题
     */
    String autoGenerateTitle(Integer sessionId);
}
