package org.linxing.linxing_agent.agent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 从 Agent 执行步骤或 RAG 搜索结果中提取来源信息
 */
@Slf4j
@Component
public class SourceExtractor {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 AgentResult 的步骤中提取来源信息，序列化为 JSON
     */
    public String extractSourcesFromSteps(List<?> steps) {
        if (steps == null || steps.isEmpty()) {
            return "[]";
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            var step = steps.get(i);
            if (step instanceof org.linxing.linxing_agent.agent.vo.AgentStepVO stepVO
                    && AgentStepTypes.TOOL_RESULT.equals(stepVO.getStepType()) && stepVO.getContent() != null) {
                try {
                    List<SearchResult> searchResults = objectMapper.readValue(
                            stepVO.getContent(),
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class, SearchResult.class));
                    return toSourcesJson(extractSourceDetails(searchResults));
                } catch (Exception e) {
                    log.debug("解析工具结果中的sources失败: {}", e.getMessage());
                }
            }
        }
        return "[]";
    }

    /**
     * 从 RAG 搜索结果中提取详细信息
     */
    public List<ChatResponse.SourceDetail> extractSourceDetails(List<SearchResult> results) {
        return results.stream()
                .map(r -> ChatResponse.SourceDetail.builder()
                        .chunkId(r.getChunkId())
                        .documentId(r.getDocumentId())
                        .fileName(r.getFileName())
                        .titlePath(r.getTitlePath())
                        .chunkType(r.getChunkType())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 序列化 sources 为 JSON
     */
    public String toSourcesJson(List<ChatResponse.SourceDetail> sourceDetails) {
        try {
            return objectMapper.writeValueAsString(sourceDetails);
        } catch (Exception e) {
            log.warn("序列化 sources 失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 从 sources JSON 中解析来源路径列表
     */
    public List<String> parseSourceList(String sourcesJson) {
        try {
            List<ChatResponse.SourceDetail> details = objectMapper.readValue(
                    sourcesJson,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ChatResponse.SourceDetail.class));
            return details.stream()
                    .map(sd -> sd.getFileName()
                            + (sd.getTitlePath() != null ? " > " + sd.getTitlePath() : ""))
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 从 sources JSON 中解析详细信息列表
     */
    public List<ChatResponse.SourceDetail> parseSourceDetails(String sourcesJson) {
        try {
            return objectMapper.readValue(
                    sourcesJson,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ChatResponse.SourceDetail.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
