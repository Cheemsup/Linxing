<template>
  <div class="notes-view">
    <NotesPanel />
  </div>
</template>

<script>
import NotesPanel from '@/components/NotesPanel.vue'

export default {
  name: 'NotesView',
  components: {
    NotesPanel
  }
}
</script>

<style scoped>
.notes-view {
  height: 100%;
}
</style>
