package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.vo.ChatSessionVO;
import org.linxing.linxing_agent.common.result.PageResult;

public interface IChatSessionService {

    ChatSessionVO createSession(Integer userId, String title);

    PageResult<ChatSessionVO> listSessions(Integer userId, int page, int size);

    void deleteSession(Integer sessionId);

    void updateTitle(Integer sessionId, String title);
}
