import { ref, onUnmounted } from 'vue'

/**
 * SSE 连接 Hook
 * 用于与后端建立 Server-Sent Events 连接，接收实时流式数据
 */
export function useSSE() {
  const eventSource = ref(null)
  const isConnected = ref(false)
  const error = ref(null)

  const connect = (url, { onMessage, onError, onOpen } = {}) => {
    if (eventSource.value) {
      eventSource.value.close()
    }

    eventSource.value = new EventSource(url)
    isConnected.value = true
    error.value = null

    eventSource.value.onopen = () => {
      if (onOpen) onOpen()
    }

    eventSource.value.onmessage = (event) => {
      if (onMessage) onMessage(event)
    }

    eventSource.value.onerror = (e) => {
      isConnected.value = false
      error.value = e
      if (onError) onError(e)
    }
  }

  const disconnect = () => {
    if (eventSource.value) {
      eventSource.value.close()
      eventSource.value = null
      isConnected.value = false
    }
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    eventSource,
    isConnected,
    error,
    connect,
    disconnect
  }
}
