<template>
  <div class="ingest-view">
    <IngestPanel />
  </div>
</template>

<script>
import IngestPanel from '@/components/agent/IngestPanel.vue'

export default {
  name: 'IngestView',
  components: {
    IngestPanel
  }
}
</script>

<style scoped>
.ingest-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafbfc;
}
</style>
