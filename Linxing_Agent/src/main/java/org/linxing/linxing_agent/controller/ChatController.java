package org.linxing.linxing_agent.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.linxing.linxing_agent.dto.ChatRequest;
import org.linxing.linxing_agent.dto.ChatResponse;
import org.linxing.linxing_agent.result.Result;
import org.linxing.linxing_agent.service.IChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class ChatController {

    private final IChatService chatService;

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request);
        return Result.success(response);
    }
}
