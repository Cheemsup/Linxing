package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;

public interface IChatService {

    ChatResponse chat(ChatRequest request);
}
