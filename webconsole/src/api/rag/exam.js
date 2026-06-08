import api from '@/api'

export const examApi = {
  getExam(examId) {
    return api.get(`/exam/${examId}`)
  },

  listExams(params) {
    return api.get('/exam', { params })
  },

  submitAnswer(examId, data) {
    return api.post(`/exam/${examId}/submit`, data)
  }
}
