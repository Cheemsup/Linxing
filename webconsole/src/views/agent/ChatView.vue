<template>
  <div class="chat-view">
    <ChatPanel />
  </div>
</template>

<script>
import ChatPanel from '@/components/agent/ChatPanel.vue'

export default {
  name: 'ChatView',
  components: {
    ChatPanel
  }
}
</script>

<style scoped>
.chat-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafbfc;
}
</style>
