package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.dto.StudyPlanExportResult;
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
     * 导出学习计划（支持 Markdown / HTML），返回内容与文件元信息
     * @param format 导出格式：md（默认）/ html
     * @return 导出结果
     */
    StudyPlanExportResult export(Integer userId, Integer planId, String format);
}
