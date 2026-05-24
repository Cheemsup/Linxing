import api from '@/api'
import { authStore } from '@/stores/authStore'

export const ragApi = {
  chatStream({ question, sessionId, parentMessageId, onMessage, onError, onDone }) {
    const token = authStore.getToken()
    const params = new URLSearchParams()
    params.append('query', question)
    if (sessionId) params.append('sessionId', String(sessionId))
    if (parentMessageId) params.append('parentMessageId', String(parentMessageId))

    fetch(`/api/agent/chat?${params.toString()}`, {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    }).then(response => {
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
            onDone?.()
            return
          }
          buffer += decoder.decode(value, { stream: true })
          const events = buffer.split('\n\n')
          buffer = events.pop()
          for (const event of events) {
            const lines = event.split('\n')
            for (const line of lines) {
              if (line.startsWith('data:')) {
                try {
                  const data = JSON.parse(line.substring(5).trim())
                  onMessage?.(data)
                } catch (e) {
                  // skip unparseable events
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
