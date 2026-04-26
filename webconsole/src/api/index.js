import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
})

api.interceptors.response.use(
  response => {
    const res = response.data
    if (res && typeof res === 'object' && 'code' in res) {
      if (res.code === 1) {
        return response
      } else {
        return Promise.reject(new Error(res.msg || '操作失败'))
      }
    }
    return response
  },
  error => {
    console.error('API Error:', error)
    return Promise.reject(error)
  }
)

export const ragApi = {
  chat(question, sessionId = 'default') {
    return api.post('/rag/chat', { question, sessionId })
  },

  ingestFile(file, userId = null) {
    const formData = new FormData()
    formData.append('file', file)
    if (userId) {
      formData.append('userId', userId)
    }
    return api.post('/ingest/file', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      },
      timeout: 120000
    })
  },

  ingest(filePath, category = '') {
    return api.post('/ingest', { filePath, category })
  }
}

export const documentApi = {
  list(page = 1, size = 10, userId = 1) {
    return api.get('/documents', {
      params: { page, size, userId }
    })
  },

  getDetail(id, userId = 1) {
    return api.get(`/documents/${id}`, {
      params: { userId }
    })
  },

  delete(id, userId = 1) {
    return api.delete(`/documents/${id}`, {
      params: { userId }
    })
  },

  preview(id, userId = 1) {
    return api.get(`/documents/${id}/preview`, {
      params: { userId }
    })
  },

  getDownloadUrl(id) {
    return `/api/documents/${id}/download`
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

export default api
