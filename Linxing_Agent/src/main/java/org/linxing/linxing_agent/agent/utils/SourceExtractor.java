package org.linxing.linxing_agent.agent.utils;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.agent.tool.impl.RagSearchTool;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 从 Agent 执行步骤或 RAG 搜索结果中提取来源信息
 */
@Slf4j
@Component
public class SourceExtractor {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 AgentResult 的步骤中提取来源信息，序列化为 JSON。
     *
     * <p>只采纳主 Agent（{@code agent_id=main}）最近一次的 {@code search_knowledge_base} 工具结果
     * 作为回答的来源，约束如下：
     * <ul>
     *   <li><b>限定工具名</b>：只认 {@link RagSearchTool#NAME}，避免其他返回 JSON 数组的工具
     *       （如工作流结果）被误判为检索来源；</li>
     *   <li><b>限定主 Agent</b>：工作流内子 Agent（如 KnowledgeCollectorAgent）的检索只服务于
     *       素材收集，其结果与最终回答未必对应，纳入会污染回答底部的来源展示；</li>
     *   <li><b>不回退更早搜索</b>：若最近一次知识库检索返回降级提示 / 非 SearchResult JSON
     *       （即候选全部被分数阈值拦下或检索失败），直接返回空来源——避免"被阈值拦下的检索内容"
     *       被链接到回答窗口底部。</li>
     * </ul>
     */
    public String extractSourcesFromSteps(List<?> steps) {
        if (steps == null || steps.isEmpty()) {
            return "[]";
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            Object step = steps.get(i);
            if (!(step instanceof AgentStepVO stepVO)
                    || !AgentStepTypes.TOOL_RESULT.equals(stepVO.getStepType())
                    || !StepRecorder.MAIN_AGENT_ID.equals(stepVO.getAgentId())
                    || stepVO.getContent() == null) {
                continue;
            }
            //只认知识库搜索工具；resolve/工作流等其他工具即使内容形似 SearchResult 列表也不采纳
            Map<String, Object> stepData = stepVO.getStepData();
            Object toolName = stepData != null ? stepData.get(AgentStepTypes.KEY_TOOL_NAME) : null;
            if (!RagSearchTool.NAME.equals(toolName)) {
                continue;
            }
            //命中最近一次主 Agent 的知识库检索：
            //  内容可解析为非空 SearchResult 列表 → 采纳为来源；
            //  否则（降级提示/空结果/失败）→ 直接返回空，不回退更早的搜索结果
            try {
                List<SearchResult> searchResults = objectMapper.readValue(
                        stepVO.getContent(),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, SearchResult.class));
                if (searchResults == null || searchResults.isEmpty()) {
                    return "[]";
                }
                return toSourcesJson(extractSourceDetails(searchResults));
            } catch (Exception e) {
                log.debug("解析工具结果中的sources失败，最近一次知识库检索为空或降级，来源置空: {}", e.getMessage());
                return "[]";
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
