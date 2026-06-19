package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.dto.ExamSubmitRequest;
import org.linxing.linxing_agent.agent.vo.ExamDetailVO;
import org.linxing.linxing_agent.agent.vo.ExamSubmitVO;
import org.linxing.linxing_agent.agent.vo.ExamVO;
import org.linxing.linxing_agent.common.result.PageResult;

import java.util.List;
import java.util.Map;

public interface IExamService {

    PageResult<ExamVO> listExams(Integer userId, String status, int page, int size);

    ExamDetailVO getExam(Integer userId, Integer examId);

    ExamSubmitVO saveAttempt(Integer userId, Integer examId, ExamSubmitRequest body);

    void saveDraft(Integer userId, Integer examId, ExamSubmitRequest body);

    Map<String, Object> getDraft(Integer userId, Integer examId);

    List<ExamVO> listByPlanId(Integer userId, Integer planId);
}
