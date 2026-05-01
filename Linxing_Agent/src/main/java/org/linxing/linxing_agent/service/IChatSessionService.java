package org.linxing.linxing_agent.service;

import org.linxing.linxing_agent.vo.ChatSessionVO;
import org.linxing.linxing_agent.dto.PageResult;

public interface IChatSessionService {

    ChatSessionVO createSession(Integer userId, String title);

    PageResult<ChatSessionVO> listSessions(Integer userId, int page, int size);

    void deleteSession(Integer sessionId);

    void updateTitle(Integer sessionId, String title);
}
