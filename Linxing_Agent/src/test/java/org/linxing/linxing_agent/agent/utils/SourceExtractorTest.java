package org.linxing.linxing_agent.agent.utils;

import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.tool.impl.RagSearchTool;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SourceExtractor 来源提取逻辑的单元测试。
 * <p>核心约束（0817 修复）：
 * <ul>
 *   <li>只采纳主 Agent（agent_id=main）的 search_knowledge_base 结果；</li>
 *   <li>只取最近一次，且最近一次返回降级/空时不回退更早的搜索结果。</li>
 * </ul>
 */
class SourceExtractorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEGRADED_TEXT = "未在知识库中检索到与查询相关的高相关内容（所有候选的相关性分数均低于阈值）。";

    private final SourceExtractor extractor = new SourceExtractor();

    // ---- helpers ----

    private String searchResultJson(SearchResult... results) {
        try {
            return OBJECT_MAPPER.writeValueAsString(List.of(results));
        } catch (Exception e) {
            throw new IllegalStateException("构造测试数据失败", e);
        }
    }

    private SearchResult chunk(int chunkId, int documentId, String fileName) {
        return SearchResult.builder()
                .chunkId(chunkId)
                .documentId(documentId)
                .fileName(fileName)
                .titlePath(null)
                .chunkType("general")
                .chunkText("片段" + chunkId)
                .score(0.99)
                .build();
    }

    private AgentStepVO toolResult(String agentId, String toolName, String content) {
        Map<String, Object> stepData = new LinkedHashMap<>();
        stepData.put(AgentStepTypes.KEY_TOOL_NAME, toolName);
        return AgentStepVO.builder()
                .stepType(AgentStepTypes.TOOL_RESULT)
                .content(content)
                .stepData(stepData)
                .agentId(agentId)
                .build();
    }

    // ---- tests ----

    @Test
    void 主Agent最近一次搜索作为来源() throws Exception {
        List<AgentStepVO> steps = List.of(
                toolResult(StepRecorder.MAIN_AGENT_ID, RagSearchTool.NAME,
                        searchResultJson(chunk(1, 10, "A.docx"), chunk(2, 10, "A.docx"))),
                toolResult(StepRecorder.MAIN_AGENT_ID, RagSearchTool.NAME,
                        searchResultJson(chunk(3, 11, "B.docx")))
        );

        String json = extractor.extractSourcesFromSteps(steps);

        assertEquals(1, extractor.parseSourceDetails(json).size());
        assertEquals(3, extractor.parseSourceDetails(json).get(0).getChunkId());
        assertEquals("B.docx", extractor.parseSourceDetails(json).get(0).getFileName());
    }

    @Test
    void 子Agent搜索结果不纳入来源() {
        List<AgentStepVO> steps = List.of(
                toolResult("KnowledgeCollectorAgent", RagSearchTool.NAME,
                        searchResultJson(chunk(99, 20, "无关笔记.docx")))
        );

        String json = extractor.extractSourcesFromSteps(steps);

        assertEquals("[]", json);
    }

    @Test
    void 最近一次搜索降级时返回空且不回退更早搜索() {
        List<AgentStepVO> steps = List.of(
                // 更早一次：有结果
                toolResult(StepRecorder.MAIN_AGENT_ID, RagSearchTool.NAME,
                        searchResultJson(chunk(1, 10, "A.docx"))),
                // 最近一次：被分数阈值拦空，返回降级文本（非 SearchResult JSON）
                toolResult(StepRecorder.MAIN_AGENT_ID, RagSearchTool.NAME, DEGRADED_TEXT)
        );

        String json = extractor.extractSourcesFromSteps(steps);

        assertEquals("[]", json);
    }

    @Test
    void 最近一次搜索返回空数组时来源为空() {
        List<AgentStepVO> steps = List.of(
                toolResult(StepRecorder.MAIN_AGENT_ID, RagSearchTool.NAME, "[]")
        );

        String json = extractor.extractSourcesFromSteps(steps);

        assertEquals("[]", json);
    }

    @Test
    void 非检索工具的JSON数组内容不误判为来源() {
        List<AgentStepVO> steps = List.of(
                toolResult(StepRecorder.MAIN_AGENT_ID, "save_exam",
                        searchResultJson(chunk(1, 10, "A.docx")))
        );

        String json = extractor.extractSourcesFromSteps(steps);

        assertEquals("[]", json);
    }

    @Test
    void 无任何检索步骤时来源为空() {
        List<AgentStepVO> steps = List.of(
                toolResult(StepRecorder.MAIN_AGENT_ID, "start_study_plan_workflow",
                        "{\"planSaved\":true}")
        );

        String json = extractor.extractSourcesFromSteps(steps);

        assertEquals("[]", json);
    }
}
