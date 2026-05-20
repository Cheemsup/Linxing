import axios from 'axios'
import { authStore } from '@/stores/authStore'

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

export default api
