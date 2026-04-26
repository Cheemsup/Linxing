<template>
  <div class="app-layout">
    <header class="app-header">
      <h1>Personal Note RAG</h1>
      <p class="subtitle">基于 LangChain4j + BGE + ChromaDB 的个人笔记知识库问答系统</p>
    </header>

    <nav class="tab-nav">
      <router-link
        v-for="tab in tabs"
        :key="tab.path"
        :to="tab.path"
        :class="['tab-btn', { active: $route.path === tab.path }]"
      >
        {{ tab.icon }} {{ tab.name }}
      </router-link>
    </nav>

    <main class="tab-content">
      <router-view />
    </main>
  </div>
</template>

<script>
export default {
  name: 'AppLayout',
  data() {
    return {
      tabs: [
        { path: '/chat', name: '智能问答', icon: '💬' },
        { path: '/ingest', name: '导入笔记', icon: '📥' },
        { path: '/notes', name: '笔记管理', icon: '📚' }
      ]
    }
  }
}
</script>

<style scoped>
.app-layout {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px;
  min-height: 100vh;
}

.app-header {
  text-align: center;
  margin-bottom: 24px;
}

.app-header h1 {
  color: #1a73e8;
  font-size: 28px;
  margin-bottom: 6px;
}

.subtitle {
  color: #666;
  font-size: 14px;
}

.tab-nav {
  display: flex;
  gap: 4px;
  background: #fff;
  border-radius: 10px;
  padding: 4px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
  margin-bottom: 20px;
}

.tab-btn {
  flex: 1;
  padding: 12px 16px;
  text-align: center;
  text-decoration: none;
  color: #666;
  border-radius: 8px;
  transition: all 0.2s;
}

.tab-btn:hover {
  background: #f5f5f5;
  color: #333;
}

.tab-btn.active {
  background: #1a73e8;
  color: white;
}

.tab-content {
  background: white;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
  min-height: 500px;
}
</style>
