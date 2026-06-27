package org.linxing.linxing_agent.agent.tool.impl;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.service.impl.StudyPlanServiceImpl;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SaveStudyPlanTool} 的 {@code @Tool} 方法单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SaveStudyPlanToolTest {

    @Mock
    private StudyPlanServiceImpl studyPlanService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SaveStudyPlanTool tool;

    @BeforeEach
    void setUp() {
        tool = new SaveStudyPlanTool(studyPlanService, objectMapper);
        SubAgentContext.bind(42, 10086);
    }

    @AfterEach
    void tearDown() {
        SubAgentContext.clear();
    }

    @Test
    void saveStudyPlan_shouldPersistAndReturnPlanId() throws Exception {
        // Given
        String containerId = "study_plan_abc123";
        ObjectNode metadata = objectMapper.createObjectNode()
                .put("title", "Rust 学习计划")
                .put("goal", "掌握 Rust 基础");
        ArrayNode phases = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("title", "第1阶段"))
                .add(objectMapper.createObjectNode().put("title", "第2阶段"));
        JsonContainer container = new JsonContainer(containerId, "study_plan", metadata,
                java.util.Map.of("phases", phases));
        SubAgentContext.currentStore().putContainer(containerId, container);

        when(studyPlanService.parseAndSave(eq(42), any(), eq(StudyPlanServiceImpl.ValidationStrategy.COLLECT_ALL)))
                .thenReturn(7);

        // When
        String result = tool.saveStudyPlan(containerId);

        // Then
        assertThat(result).contains("\"planId\":7").contains("\"phaseCount\":2");
        verify(studyPlanService).parseAndSave(eq(42), any(), eq(StudyPlanServiceImpl.ValidationStrategy.COLLECT_ALL));
    }

    @Test
    void saveStudyPlan_shouldRejectWrongContainerType() {
        String containerId = "exam_abc123";
        JsonContainer container = new JsonContainer(containerId, "exam",
                objectMapper.createObjectNode(), java.util.Map.of());
        SubAgentContext.currentStore().putContainer(containerId, container);

        String result = tool.saveStudyPlan(containerId);

        assertThat(result).startsWith("错误：容器类型不匹配");
    }

    @Test
    void saveStudyPlan_shouldReportMissingContainer() {
        String result = tool.saveStudyPlan("non_existent");
        assertThat(result).startsWith("错误：容器不存在");
    }

    @Test
    void saveStudyPlan_shouldReportMissingUserId() {
        SubAgentContext.clear();
        String result = tool.saveStudyPlan("study_plan_x");
        assertThat(result).startsWith("错误：用户未登录");
    }
}
