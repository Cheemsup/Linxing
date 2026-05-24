import api from '@/api'

export const chunkApi = {
  getContext(id) {
    return api.get(`/rag/chunks/${id}/context`)
  }
}
