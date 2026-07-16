<template>
  <div class="chat-session-view">
    <ChatPanel />
  </div>
</template>

<script>
import ChatPanel from '@/components/agent/ChatPanel.vue'

export default {
  name: 'ChatSessionView',
  components: {
    ChatPanel
  }
}
</script>

<style scoped>
.chat-session-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafbfc;
}
</style>
