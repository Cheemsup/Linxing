import api from '@/api'
import { authStore } from '@/stores/authStore'

const BACKEND_BASE = process.env.VUE_APP_SSE_BASE_URL || ''

export const ragApi = {
  chatStream({ question, sessionId, parentMessageId, onMessage, onError, onDone }) {
    const token = authStore.getToken()
    const params = new URLSearchParams()
    params.append('query', question)
    if (sessionId) params.append('sessionId', String(sessionId))
    if (parentMessageId) params.append('parentMessageId', String(parentMessageId))

    fetch(`${BACKEND_BASE}/agent/chat?${params.toString()}`, {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    }).then(response => {
      // console.log('[SSE-DEBUG] Response status:', response.status)
      // console.log('[SSE-DEBUG] Content-Type:', response.headers.get('content-type'))

      if (!response.ok) {
        response.text().then(text => {
          onError?.(new Error(text || `HTTP ${response.status}`))
        })
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) {
            // console.log('[SSE-DEBUG] 流读取完成(done)')
            onDone?.()
            return
          }
          const chunk = decoder.decode(value, { stream: true })
          // console.log('[SSE-DEBUG] chunk到达, 长度:', chunk.length)
          buffer += chunk
          const events = buffer.split('\n\n')
          buffer = events.pop()
          for (const event of events) {
            const lines = event.split('\n')
            for (const line of lines) {
              if (line.startsWith('data:')) {
                try {
                  const data = JSON.parse(line.substring(5).trim())
                  // 实时输出接收到的token（调试用，取消注释可查看）
                  // if (data.type === 'llm_stream') console.log('[SSE-DEBUG] token:', data.token)
                  onMessage?.(data)
                } catch (e) {
                  // console.warn('[SSE-DEBUG] 解析失败:', line, e)
                }
              }
            }
          }
          read()
        }).catch(err => {
          onError?.(err)
        })
      }
      read()
    }).catch(err => {
      onError?.(err)
    })
  }
}

export const chatSessionApi = {
  create(title) {
    return api.post('/agent/sessions', { title })
  },
  list(page = 1, size = 20) {
    return api.get('/agent/sessions', { params: { page, size } })
  },
  delete(id) {
    return api.delete(`/agent/sessions/${id}`)
  },
  getMessages(sessionId) {
    return api.get(`/agent/sessions/${sessionId}/messages`)
  },
  deleteSubtree(messageId) {
    return api.delete(`/agent/messages/${messageId}/subtree`)
  }
}
