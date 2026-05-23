import api from '@/api'

export const searchApi = {
  search({ query, topK, hybrid }) {
    return api.post('/search', { query, topK, hybrid })
  }
}
