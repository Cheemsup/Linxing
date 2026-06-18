package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.dto.StudyPlanProgressUpdateRequest;
import org.linxing.linxing_agent.agent.vo.StudyPlanDetailVO;
import org.linxing.linxing_agent.agent.vo.StudyPlanVO;
import org.linxing.linxing_agent.common.result.PageResult;

/**
 * 学习计划服务接口
 */
public interface IStudyPlanService {

    /**
     * 分页查询用户的学习计划列表
     */
    PageResult<StudyPlanVO> listPlans(Integer userId, String status, int page, int size);

    /**
     * 获取学习计划详情（含阶段列表与进度）
     */
    StudyPlanDetailVO getPlanDetail(Integer userId, Integer planId);

    /**
     * 更新阶段进度状态
     */
    void updatePhaseStatus(Integer userId, Integer planId, Integer phaseId, StudyPlanProgressUpdateRequest body);

    /**
     * 导出学习计划为 Markdown 字符串
     */
    String exportAsMarkdown(Integer userId, Integer planId);

    /**
     * 导出学习计划为 HTML 字符串
     */
    String exportAsHtml(Integer userId, Integer planId);
}
