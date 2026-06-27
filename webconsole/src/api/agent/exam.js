import api from '@/api'

export const examApi = {
  getExam(examId) {
    return api.get(`/exam/${examId}`)
  },

  listExams(params) {
    return api.get('/exam', { params })
  },

  /**
   * 查询关联到指定学习计划的测验列表
   * @param {number} planId 学习计划 ID
   * @returns {Promise} 测验列表
   */
  listByPlanId(planId) {
    return api.get(`/exam/by-plan/${planId}`)
  },

  submitAnswer(examId, data) {
    return api.post(`/exam/${examId}/submit`, data)
  },

  saveDraft(examId, data) {
    return api.post(`/exam/${examId}/draft`, data)
  },

  getDraft(examId) {
    return api.get(`/exam/${examId}/draft`)
  }
}
