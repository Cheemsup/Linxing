import api from '@/api'

export const studyPlanApi = {
  getPlanDetail(planId) {
    return api.get(`/study-plan/${planId}`)
  },

  listPlans(params) {
    return api.get('/study-plan', { params })
  },

  updatePhaseStatus(planId, phaseId, data) {
    return api.put(`/study-plan/${planId}/phase/${phaseId}/progress`, data)
  },

  exportPlan(planId, format = 'md') {
    return api.get(`/study-plan/${planId}/export`, {
      params: { format },
      responseType: 'blob'
    })
  }
}
