import api from '@/api'

export const documentApi = {
  list(page = 1, size = 10) {
    return api.get('/rag/documents', {
      params: { page, size }
    })
  },

  getDetail(id) {
    return api.get(`/rag/documents/${id}`)
  },

  delete(id) {
    return api.delete(`/rag/documents/${id}`)
  },

  preview(id) {
    return api.get(`/rag/documents/${id}/preview`)
  },

  getDownloadUrl(id) {
    return `/api/rag/documents/${id}/download`
  },

  download(id) {
    return api.get(`/rag/documents/${id}/download`, {
      responseType: 'blob'
    })
  }
}

export const noteApi = {
  list() {
    return documentApi.list(1, 100)
  },

  get(id) {
    return documentApi.getDetail(id)
  },

  delete(id) {
    return documentApi.delete(id)
  }
}
