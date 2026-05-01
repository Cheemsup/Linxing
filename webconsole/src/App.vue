<template>
  <div id="app">
    <AppLayout />
  </div>
</template>

<script>
import AppLayout from './layouts/AppLayout.vue'

export default {
  name: 'App',
  components: {
    AppLayout
  }
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Microsoft YaHei', sans-serif;
  background: #f0f2f5;
  color: #333;
  height: 100%;
  overflow: hidden;
}

#app {
  height: 100%;
}
</style>
