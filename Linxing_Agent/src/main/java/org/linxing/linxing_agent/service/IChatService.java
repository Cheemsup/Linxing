package org.linxing.linxing_agent.service;

import org.linxing.linxing_agent.dto.ChatRequest;
import org.linxing.linxing_agent.dto.ChatResponse;

public interface IChatService {

    ChatResponse chat(ChatRequest request);
}
