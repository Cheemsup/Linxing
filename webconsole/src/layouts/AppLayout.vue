<template>
  <div class="app-layout">
    <header class="app-header">
      <h1>Personal Note RAG</h1>
      <p class="subtitle">基于 LangChain4j + BGE + PostgreSQL/pgvector 的个人笔记知识库问答系统</p>
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
import { authStore } from '@/utils/authStore'

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
  display: flex;
  flex-direction: column;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: #f0f2f5;
}

.app-header {
  text-align: center;
  padding: 12px 24px;
  background: linear-gradient(135deg, #1a73e8 0%, #4285f4 100%);
  color: white;
  flex-shrink: 0;
}

.app-header h1 {
  font-size: 22px;
  margin-bottom: 2px;
  color: white;
}

.subtitle {
  color: rgba(255, 255, 255, 0.8);
  font-size: 12px;
}

.tab-nav {
  display: flex;
  gap: 0;
  background: #fff;
  padding: 0 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  align-items: center;
  flex-shrink: 0;
  border-bottom: 1px solid #e8e8e8;
}

.tab-btn {
  padding: 14px 20px;
  text-align: center;
  text-decoration: none;
  color: #666;
  transition: all 0.2s;
  font-size: 14px;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}

.tab-btn:hover {
  color: #1a73e8;
  background: #f5f8ff;
}

.tab-btn.active {
  color: #1a73e8;
  border-bottom-color: #1a73e8;
  font-weight: 600;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.username {
  color: #333;
  font-size: 13px;
  font-weight: 500;
}

.logout-btn {
  padding: 5px 14px;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 6px;
  color: #666;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s;
}

.logout-btn:hover {
  background: #fff3f3;
  border-color: #d32f2f;
  color: #d32f2f;
}

.tab-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: white;
}

@media (max-width: 768px) {
  .tab-nav {
    flex-wrap: wrap;
    padding: 0 10px;
  }

  .tab-btn {
    padding: 12px 14px;
    font-size: 13px;
  }

  .user-info {
    width: 100%;
    justify-content: flex-end;
    margin-left: 0;
    padding: 6px 10px;
    border-top: 1px solid #eee;
  }
}
</style>
