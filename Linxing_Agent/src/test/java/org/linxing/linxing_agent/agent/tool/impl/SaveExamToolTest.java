package org.linxing.linxing_agent.agent.tool.impl;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.service.impl.ExamService;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SaveExamTool} 的 {@code @Tool} 方法单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SaveExamToolTest {

    @Mock
    private ExamService examService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SaveExamTool tool;

    @BeforeEach
    void setUp() {
        tool = new SaveExamTool(examService, objectMapper);
        SubAgentContext.bind(42, 10086);
    }

    @AfterEach
    void tearDown() {
        SubAgentContext.clear();
    }

    @Test
    void saveExam_shouldPersistWithLinkedPlanId() throws Exception {
        // Given
        String containerId = "exam_abc123";
        ObjectNode metadata = objectMapper.createObjectNode().put("title", "Rust 测验");
        ArrayNode questions = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("type", "single_choice")
                        .put("stem", "题干")
                        .put("answer", "A"));
        JsonContainer container = new JsonContainer(containerId, "exam", metadata,
                java.util.Map.of("questions", questions));
        SubAgentContext.currentStore().putContainer(containerId, container);

        when(examService.parseAndSave(eq(42), any(), eq(ExamService.ValidationStrategy.COLLECT_ALL), eq(7)))
                .thenReturn(9);

        // When
        String result = tool.saveExam(containerId, "7");

        // Then
        assertThat(result).contains("\"examId\":9").contains("\"questionCount\":1");
        verify(examService).parseAndSave(eq(42), any(), eq(ExamService.ValidationStrategy.COLLECT_ALL), eq(7));
    }

    @Test
    void saveExam_shouldAcceptNullLinkedPlanId() throws Exception {
        // Given
        String containerId = "exam_def456";
        ObjectNode metadata = objectMapper.createObjectNode().put("title", "独立测验");
        ArrayNode questions = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("type", "true_false")
                        .put("stem", "题干")
                        .put("answer", "正确"));
        JsonContainer container = new JsonContainer(containerId, "exam", metadata,
                java.util.Map.of("questions", questions));
        SubAgentContext.currentStore().putContainer(containerId, container);

        when(examService.parseAndSave(eq(42), any(), eq(ExamService.ValidationStrategy.COLLECT_ALL), isNull()))
                .thenReturn(11);

        // When
        String result = tool.saveExam(containerId, "");

        // Then
        assertThat(result).contains("\"examId\":11");
        verify(examService).parseAndSave(eq(42), any(), eq(ExamService.ValidationStrategy.COLLECT_ALL), isNull());
    }

    @Test
    void saveExam_shouldRejectWrongContainerType() {
        String containerId = "study_plan_abc123";
        JsonContainer container = new JsonContainer(containerId, "study_plan",
                objectMapper.createObjectNode(), java.util.Map.of());
        SubAgentContext.currentStore().putContainer(containerId, container);

        String result = tool.saveExam(containerId, "");

        assertThat(result).startsWith("错误：容器类型不匹配");
    }
}
