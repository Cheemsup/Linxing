<template>
  <div class="chat-home-view">
    <ChatHomePanel />
  </div>
</template>

<script>
import ChatHomePanel from '@/components/agent/ChatHomePanel.vue'

export default {
  name: 'ChatHomeView',
  components: {
    ChatHomePanel
  }
}
</script>

<style scoped>
.chat-home-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #faf8f4;
}
</style>
