import api from '@/api'

export const documentApi = {
  list(page = 1, size = 10) {
    return api.get('/documents', {
      params: { page, size }
    })
  },

  getDetail(id) {
    return api.get(`/documents/${id}`)
  },

  delete(id) {
    return api.delete(`/documents/${id}`)
  },

  preview(id) {
    return api.get(`/documents/${id}/preview`)
  },

  getChunkTree(id) {
    return api.get(`/documents/${id}/chunk-tree`)
  },

  getDownloadUrl(id) {
    return `/api/documents/${id}/download`
  },

  download(id) {
    return api.get(`/documents/${id}/download`, {
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

  save(note) {
    return api.post('/notes', note)
  },

  delete(id) {
    return documentApi.delete(id)
  }
}
