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
    // 幂等键：每次 chatStream 调用生成唯一 requestId，SSE reset 后退避重试时复用同一值，
    // 后端按 requestId 命中已完成结果缓存则直接复用推送，不重跑推理、不重复落库。
    // crypto.randomUUID 在安全上下文（HTTPS / localhost）下可用；兜底用时间戳+随机数。
    const requestId = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : String(Date.now()) + '-' + Math.random().toString(16).slice(2)
    const body = { question }
    if (sessionId) body.sessionId = sessionId
    if (parentMessageId) body.parentMessageId = parentMessageId
    body.requestId = requestId

    // 退避重试：网络层失败（fetch reject / reader read reject）时，复用同一 requestId 退避重试。
    // 后端命中缓存则直接复用已完成结果，不重跑推理；进行中则返回"处理中"。
    // 最多重试 2 次，base 1s × factor 2（1s、2s）；HTTP 非 2xx 视为业务错误不重试。
    const MAX_RETRY = 2
    const BASE_DELAY = 1000
    const RETRY_FACTOR = 2

    let retryCount = 0

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

    function startStream() {
      fetch(`${SSE_BASE}/agent/chat`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(body)
      }).then(response => {
        if (!response.ok) {
          // HTTP 非 2xx 视为业务错误，不退避重试
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
            // 流读取中断（网络 reset 等）→ 退避重试复用同一 requestId
            handleRetryableFailure(err)
          })
        }

        read()
      }).catch(err => {
        // fetch 本身失败（连接不上）→ 退避重试复用同一 requestId
        handleRetryableFailure(err)
      })
    }

    function handleRetryableFailure(err) {
      if (retryCount < MAX_RETRY) {
        const delay = BASE_DELAY * Math.pow(RETRY_FACTOR, retryCount)
        retryCount++
        console.warn(`[SSE] 连接失败，${delay}ms 后退避重试（第${retryCount}/${MAX_RETRY}次），复用 requestId=${requestId}`, err.message)
        setTimeout(startStream, delay)
      } else {
        onError?.({ message: err.message || '连接失败（已重试上限）' })
      }
    }

    startStream()
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
  updateTitle(id, title) {
    return api.put(`/agent/sessions/${id}/title`, { title })
  },
  autoTitle(id) {
    return api.post(`/agent/sessions/${id}/auto-title`)
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
