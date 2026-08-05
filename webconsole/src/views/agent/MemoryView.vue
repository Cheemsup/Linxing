<template>
  <div class="memory-view">
    <MemoryPanel />
  </div>
</template>

<script>
import MemoryPanel from '@/components/agent/MemoryPanel.vue'

export default {
  name: 'MemoryView',
  components: {
    MemoryPanel
  }
}
</script>

<style scoped>
.memory-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #faf8f4;
}
</style>