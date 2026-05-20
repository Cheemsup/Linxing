package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.dto.ChatRequest;
import org.linxing.linxing_agent.rag.dto.ChatResponse;

public interface IChatService {

    ChatResponse chat(ChatRequest request);
}
