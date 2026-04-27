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

      <div v-if="isLoggedIn" class="user-info">
        <span class="username">{{ username }}</span>
        <button @click="handleLogout" class="logout-btn">退出登录</button>
      </div>
    </nav>

    <main class="tab-content">
      <router-view />
    </main>
  </div>
</template>

<script>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { authStore } from '@/utils/auth'

export default {
  name: 'AppLayout',
  setup() {
    const router = useRouter()

    const tabs = [
      { path: '/chat', name: '智能问答', icon: '💬' },
      { path: '/ingest', name: '导入笔记', icon: '📥' },
      { path: '/notes', name: '笔记管理', icon: '📚' }
    ]

    const isLoggedIn = computed(() => authStore.isAuthenticated())
    const username = computed(() => authStore.getUsername() || '用户')

    const handleLogout = () => {
      authStore.clearAuth()
      router.push('/login')
    }

    return {
      tabs,
      isLoggedIn,
      username,
      handleLogout
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
  align-items: center;
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

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
  padding-right: 4px;
}

.username {
  color: #333;
  font-size: 14px;
  font-weight: 500;
}

.logout-btn {
  padding: 6px 16px;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 6px;
  color: #666;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.logout-btn:hover {
  background: #fff3f3;
  border-color: #d32f2f;
  color: #d32f2f;
}

.tab-content {
  background: white;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
  min-height: 500px;
}

@media (max-width: 768px) {
  .tab-nav {
    flex-wrap: wrap;
  }

  .tab-btn {
    flex: 1 1 calc(33.333% - 4px);
    min-width: 100px;
  }

  .user-info {
    width: 100%;
    justify-content: center;
    margin-left: 0;
    margin-top: 8px;
    padding: 8px;
    border-top: 1px solid #eee;
  }
}
</style>
