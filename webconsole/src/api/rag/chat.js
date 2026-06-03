import api from '@/api'
import { authStore } from '@/stores/authStore'

// SSE 流式请求直连后端，绕过 Vue DevServer 代理的缓冲问题
// 开发环境通过 .env.development 配置 VUE_APP_SSE_BASE_URL=http://localhost:8080
// 生产环境留空，走 Nginx 反代（已配置 X-Accel-Buffering: no）
const SSE_BASE = process.env.VUE_APP_SSE_BASE_URL || ''

/**
 * SSE 流式聊天接口
 *
 * 回调接口（类型化，按 event name 分发）：
 *   onStep(data)   — event: step   (推理步骤、工具调用、最终回答等)
 *   onStream(data)  — event: stream (流式 token，携带 stepNumber)
 *   onResult(data)  — event: result (最终结果，含 answer/sources/sessionId/messageId)
 *   onDone()        — event: done   (流结束)
 *   onError(data)   — event: error  (服务端错误)
 */
export const ragApi = {
  chatStream({ question, sessionId, parentMessageId, onStep, onStream, onResult, onDone, onError }) {
    const token = authStore.getToken()
    const body = { question }
    if (sessionId) body.sessionId = sessionId
    if (parentMessageId) body.parentMessageId = parentMessageId

    fetch(`${SSE_BASE}/agent/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(body)
    }).then(response => {
      if (!response.ok) {
        response.text().then(text => {
          onError?.({ message: text || `HTTP ${response.status}` })
        })
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) {
            onDone?.()
            return
          }
          const chunk = decoder.decode(value, { stream: true })
          buffer += chunk
          // 兼容 \r\n 和 \n 换行，事件间以空行分隔
          const events = buffer.split(/\r?\n\r?\n/)
          buffer = events.pop()
          for (const event of events) {
            let eventName = ''
            let dataStr = ''
            const lines = event.split(/\r?\n/)
            for (const line of lines) {
              if (line.startsWith('event:')) {
                eventName = line.substring(6).trim()
              } else if (line.startsWith('data:')) {
                // data: 后允许零或一个空格
                dataStr = line.substring(5).replace(/^ ?/, '')
              }
            }
            if (!eventName || dataStr === undefined) continue
            dispatchEvent(eventName, dataStr)
          }
          read()
        }).catch(err => {
          onError?.({ message: err.message || '连接失败' })
        })
      }

      function dispatchEvent(eventName, dataStr) {
        switch (eventName) {
          case 'step':
          case 'stream':
          case 'result': {
            try {
              const data = JSON.parse(dataStr)
              if (eventName === 'step') onStep?.(data)
              else if (eventName === 'stream') onStream?.(data)
              else onResult?.(data)
            } catch (e) {
              console.warn('[SSE] JSON 解析失败:', eventName, dataStr)
            }
            break
          }
          case 'done':
            onDone?.()
            break
          case 'error': {
            try {
              const data = JSON.parse(dataStr)
              onError?.(data)
            } catch (e) {
              onError?.({ message: dataStr })
            }
            break
          }
          default:
            console.warn('[SSE] 未知事件类型:', eventName)
        }
      }

      read()
    }).catch(err => {
      onError?.({ message: err.message || '连接失败' })
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
  },
  getMessageSteps(messageId) {
    return api.get(`/agent/messages/${messageId}/steps`)
  }
}
