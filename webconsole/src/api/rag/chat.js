import api from '@/api'

export const ragApi = {
  chat({ question, sessionId, parentMessageId }) {
    return api.post('/rag/chat', { question, sessionId, parentMessageId })
  }
}

export const chatSessionApi = {
  create(title) {
    return api.post('/rag/sessions', { title })
  },
  list(page = 1, size = 20) {
    return api.get('/rag/sessions', { params: { page, size } })
  },
  delete(id) {
    return api.delete(`/rag/sessions/${id}`)
  },
  getMessages(sessionId) {
    return api.get(`/rag/sessions/${sessionId}/messages`)
  },
  deleteSubtree(messageId) {
    return api.delete(`/rag/messages/${messageId}/subtree`)
  }
}
