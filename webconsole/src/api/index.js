import axios from 'axios'
import { authStore } from '@/utils/auth'

const api = axios.create({
  baseURL: '/api',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
})

api.interceptors.request.use(
  config => {
    const token = authStore.getToken()
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  error => {
    console.error('Request Error:', error)
    return Promise.reject(error)
  }
)

api.interceptors.response.use(
  response => {
    return response
  },
  error => {
    if (error.response) {
      const status = error.response.status

      switch (status) {
        case 401:
          authStore.clearAuth()
          window.location.href = '/login'
          break
        case 403:
          console.error('权限不足，拒绝访问')
          break
        case 404:
          console.error('请求的资源不存在')
          break
        case 500:
          console.error('服务器内部错误')
          break
        default:
          console.error(`请求错误: ${status}`)
      }
    } else if (error.message.includes('timeout')) {
      console.error('请求超时')
    } else {
      console.error('网络错误，请检查连接')
    }

    return Promise.reject(error)
  }
)

export const ragApi = {
  chat({ question, sessionId, parentMessageId }) {
    return api.post('/rag/chat', { question, sessionId, parentMessageId })
  },

  ingestFile(file) {
    const formData = new FormData()
    formData.append('file', file)
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

export const chatSessionApi = {
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
  }
}

export const chunkApi = {
  getContext(id) {
    return api.get(`/chunks/${id}/context`)
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
