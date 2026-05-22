package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.vo.ChatSessionVO;
import org.linxing.linxing_agent.common.result.PageResult;

public interface IChatSessionService {

    ChatSessionVO createSession(Integer userId, String title);

    PageResult<ChatSessionVO> listSessions(Integer userId, int page, int size);

    void deleteSession(Integer sessionId);

    void updateTitle(Integer sessionId, String title);
}
