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

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: #f0f2f5;
  color: #333;
  min-height: 100vh;
}

#app {
  min-height: 100vh;
}
</style>
