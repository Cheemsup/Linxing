import api from '@/api'

export const chunkApi = {
  getContext(id) {
    return api.get(`/chunks/${id}/context`)
  }
}
