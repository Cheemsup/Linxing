<template>
  <div class="app-layout" :class="{ collapsed: isCollapsed }">
    <!-- 左侧边栏：可展开 / 关闭 -->
    <aside class="app-sidebar">
      <!-- 顶部：品牌 + 折叠按钮 -->
      <div class="sidebar-head">
        <div class="brand">
          <svg class="brand-mark" viewBox="0 0 48 48" width="26" height="26" aria-hidden="true">
            <path
              d="M24 3 L27.5 20.5 L45 24 L27.5 27.5 L24 45 L20.5 27.5 L3 24 L20.5 20.5 Z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linejoin="round"
            />
          </svg>
          <span class="brand-name">临星</span>
        </div>
        <button class="collapse-btn" @click="toggleCollapse" :title="isCollapsed ? '展开侧栏' : '收起侧栏'">
          <el-icon><component :is="isCollapsed ? 'Expand' : 'Fold'" /></el-icon>
        </button>
      </div>

      <!-- 导航分组（可折叠） -->
      <nav class="sidebar-nav">
        <div v-for="group in navGroups" :key="group.label" class="nav-group">
          <div
            v-if="!isCollapsed"
            class="group-label"
            :class="{ clickable: group.collapsible }"
            @click="toggleGroup(group)"
          >
            <span>{{ group.label }}</span>
            <el-icon v-if="group.collapsible" class="group-chevron">
              <component :is="isGroupCollapsed(group) ? 'ArrowDown' : 'ArrowUp'" />
            </el-icon>
          </div>
          <div v-show="isCollapsed || !group.collapsible || !isGroupCollapsed(group)" class="nav-items">
            <el-tooltip
              v-for="item in group.items"
              :key="item.path"
              :content="item.name"
              placement="right"
              :disabled="!isCollapsed"
              :show-after="200"
            >
              <router-link
                :to="item.path"
                class="nav-item"
                :class="{ active: isActive(item) }"
                @click="onNavClick(item)"
              >
                <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
                <span class="nav-text">{{ item.name }}</span>
              </router-link>
            </el-tooltip>
          </div>
        </div>
      </nav>

      <!-- 对话历史（KIMI 风格：置于侧栏底部，吃剩余空间） -->
      <div class="chat-history">
        <div v-if="!isCollapsed" class="history-header">
          <span class="history-label">对话历史</span>
        </div>
        <div v-if="!isCollapsed" class="history-list">
          <div
            v-for="s in chatSessions"
            :key="s.id"
            class="history-item"
            :class="{ active: isHistoryActive(s.id), editing: editingId === s.id }"
            @click="switchToSession(s.id)"
          >
            <el-icon class="history-icon"><ChatLineRound /></el-icon>
            <!-- 就地编辑态：标题原地变 input，回车/失焦保存、Esc 取消 -->
            <input
              v-if="editingId === s.id"
              ref="titleInputRef"
              v-model="editingTitle"
              class="history-title-input"
              maxlength="30"
              @click.stop
              @keyup.enter="saveTitle(s.id)"
              @keyup.esc="cancelEdit"
              @blur="saveTitle(s.id)"
            />
            <span v-else class="history-title">{{ s.title }}</span>
            <!-- 操作菜单：hover 显「⋯」，点击展开（编辑态下隐藏，避免误触） -->
            <el-dropdown
              v-if="editingId !== s.id"
              trigger="click"
              placement="bottom-end"
              @command="onMenuCommand($event, s)"
              @click.stop
            >
              <button class="history-more" @click.stop title="更多操作">
                <el-icon><MoreFilled /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="rename" :icon="EditPen">更改对话标题</el-dropdown-item>
                  <el-dropdown-item command="delete" :icon="Delete" divided>删除对话</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div v-if="!chatSessions.length" class="history-empty">暂无对话记录</div>
        </div>
      </div>

      <!-- 底部：用户 + 退出 -->
      <div class="sidebar-foot">
        <el-tooltip :content="username" placement="right" :disabled="!isCollapsed" :show-after="200">
          <div class="user-info">
            <el-icon class="user-avatar"><User /></el-icon>
            <span class="username">{{ username }}</span>
          </div>
        </el-tooltip>
        <el-tooltip content="退出登录" placement="right" :disabled="!isCollapsed" :show-after="200">
          <button class="logout-btn" @click="handleLogout">
            <el-icon><Right /></el-icon>
            <span class="logout-text">退出登录</span>
          </button>
        </el-tooltip>
      </div>
    </aside>

    <!-- 右侧主区域 -->
    <section class="app-main">
      <div class="breadcrumb-bar">
        <el-icon class="breadcrumb-home"><HomeFilled /></el-icon>
        <span class="breadcrumb-sep">/</span>
        <span class="breadcrumb-current">{{ currentTitle }}</span>
      </div>
      <div class="main-content">
        <router-view />
      </div>
    </section>
  </div>
</template>

<script>
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, EditPen, Delete } from '@element-plus/icons-vue'
import { authStore } from '@/stores/authStore'
import { chatSessionStore } from '@/stores/agent/chatSessionStore'
import { chatSessionApi } from '@/api/agent/chat'

const COLLAPSE_KEY = 'linxing_sidebar_collapsed'

export default {
  name: 'AppLayout',
  setup() {
    const router = useRouter()
    const route = useRoute()

    // 侧边栏折叠状态，持久化到 localStorage
    const isCollapsed = ref(localStorage.getItem(COLLAPSE_KEY) === 'true')

    const toggleCollapse = () => {
      isCollapsed.value = !isCollapsed.value
      localStorage.setItem(COLLAPSE_KEY, String(isCollapsed.value))
    }

    // 按内容生命周期分组：对话 / 知识库 / 我的学习
    // collapsible: true 表示该分组标题可点击折叠/展开
    const navGroups = [
      {
        label: '对话',
        collapsible: false,
        items: [
          { path: '/chat', name: '新对话', icon: 'ChatDotRound' }
        ]
      },
      {
        label: '知识库',
        collapsible: true,
        items: [
          { path: '/notes', name: '笔记管理', icon: 'Notebook' },
          { path: '/ingest', name: '导入笔记', icon: 'Upload' },
          { path: '/search', name: '搜索笔记', icon: 'Search' }
        ]
      },
      {
        label: '我的学习',
        collapsible: true,
        items: [
          { path: '/study-plan', name: '学习计划', icon: 'Calendar' },
          { path: '/quiz', name: '知识测验', icon: 'EditPen' }
        ]
      }
    ]

    // 折叠状态：记录已折叠的分组 label，持久化到 localStorage
    const COLLAPSED_GROUPS_KEY = 'linxing_sidebar_collapsed_groups'
    const collapsedGroups = ref(new Set(
      JSON.parse(localStorage.getItem(COLLAPSED_GROUPS_KEY) || '[]')
    ))

    const isGroupCollapsed = (group) => collapsedGroups.value.has(group.label)

    const toggleGroup = (group) => {
      if (!group.collapsible) return
      const next = new Set(collapsedGroups.value)
      if (next.has(group.label)) {
        next.delete(group.label)
      } else {
        next.add(group.label)
      }
      collapsedGroups.value = next
      localStorage.setItem(COLLAPSED_GROUPS_KEY, JSON.stringify([...next]))
    }

    // 统一的导航项激活判定：所有菜单项（含「新对话」与「对话历史」）共用同一套逻辑，
    // 保证任一时刻至多一个高亮，避免多区块同时亮起。
    // - 非对话类项：路由前缀匹配，详情页（/quiz/:id、/study-plan/:id）也能高亮父级。
    // - 对话类项：首页态（/chat/home 与 redirect 落点 /chat）时高亮「新对话」，
    //   /chat/:sessionId 下由路由 params 在历史项中二选一（见 isHistoryActive）。
    const isActive = (item) => {
      if (item.path === '/chat') {
        return route.path === '/chat' || route.path === '/chat/home'
      }
      if (route.path === item.path) return true
      return route.path.startsWith(item.path + '/')
    }

    // 点击「新对话」清空活跃会话（KIMI 风格），router-link 随后导航到 /chat，由其固定 redirect 落首页
    const onNavClick = (item) => {
      if (item.path === '/chat') {
        chatSessionStore.startNewChat()
      }
    }

    const currentTitle = computed(() => route.meta.title || '临星')
    const isLoggedIn = computed(() => authStore.isAuthenticated())
    const username = computed(() => authStore.getUsername() || '用户')

    // 对话历史（来自共享 store）
    const chatSessions = computed(() => chatSessionStore.state.sessions)

    // 对话历史项激活判定：当前路由落在 /chat/:sessionId，且该会话正是路由 params 对应会话。
    // 以路由 params 为准（而非 activeSessionId），与「新对话」互斥，与知识库/学习区以路由互斥。
    const isHistoryActive = (id) => {
      if (!route.path.startsWith('/chat/')) return false//排除 /chat/home 首页
      return route.params.sessionId === String(id)
    }

    const switchToSession = (id) => {
      chatSessionStore.setActiveSession(id)
      router.push(`/chat/${id}`)
    }

    const deleteSession = async (id) => {
      try {
        await ElMessageBox.confirm('确定删除此对话？此操作不可撤销。', '删除对话', {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          type: 'warning'
        })
      } catch {
        return // 用户取消
      }
      // 删除当前路由对应的会话时，先跳首页再删，避免 URL 停留在已删会话上
      const isActiveSession = String(id) === route.params.sessionId
      if (isActiveSession) {
        router.push('/chat/home')
      }
      await chatSessionStore.deleteSession(id)
    }

    // —— 就地标题编辑 ——
    // editingId 命中某条目时，该条目标题渲染为 input；回车/失焦保存，Esc 取消
    const editingId = ref(null)
    const editingTitle = ref('')
    const titleInputRef = ref(null)

    const startEdit = (s) => {
      editingId.value = s.id
      editingTitle.value = s.title || ''
      // v-for 内的 ref 是数组；同一时刻仅一个 input 渲染（editingId 唯一），取首个
      nextTick(() => {
        const el = Array.isArray(titleInputRef.value) ? titleInputRef.value[0] : titleInputRef.value
        el?.focus?.()
      })
    }

    const cancelEdit = () => {
      editingId.value = null
      editingTitle.value = ''
    }

    const saveTitle = async (id) => {
      if (editingId.value !== id) return // blur 可能重复触发，守卫之
      const newTitle = editingTitle.value.trim()
      const current = chatSessionStore.state.sessions.find(s => s.id === id)
      // 空标题或未改动：直接取消，不发请求
      if (!newTitle || newTitle === (current?.title || '')) {
        cancelEdit()
        return
      }
      try {
        await chatSessionApi.updateTitle(id, newTitle)
        chatSessionStore.updateSessionTitle(id, newTitle) // 共享 store 更新，ChatPanel 头部标题随之响应
      } catch (e) {
        ElMessage.error('标题更新失败')
        console.warn('标题更新失败:', e)
      } finally {
        cancelEdit()
      }
    }

    // 下拉菜单命令分发
    const onMenuCommand = (cmd, s) => {
      if (cmd === 'rename') startEdit(s)
      else if (cmd === 'delete') deleteSession(s.id)
    }

    const handleLogout = () => {
      authStore.clearAuth()
      router.push('/login')
    }

    onMounted(() => {
      chatSessionStore.fetchSessions()
    })

    return {
      isCollapsed,
      toggleCollapse,
      navGroups,
      collapsedGroups,
      isGroupCollapsed,
      toggleGroup,
      isActive,
      isHistoryActive,
      onNavClick,
      currentTitle,
      isLoggedIn,
      username,
      chatSessions,
      switchToSession,
      deleteSession,
      // 就地标题编辑
      editingId,
      editingTitle,
      titleInputRef,
      startEdit,
      cancelEdit,
      saveTitle,
      // 操作菜单
      onMenuCommand,
      MoreFilled,
      EditPen,
      Delete,
      handleLogout
    }
  }
}
</script>

<style scoped>
/* 设计 token：与登录页一致 —— 深墨绿 + 暖米白 + 琥珀强调 */
.app-layout {
  --ink: #1a2e2a;
  --ink-soft: #4a5a55;
  --ink-mute: #8a948f;
  --paper: #faf8f4;
  --paper-2: #f1ece3;
  --line: #d9d2c4;
  --line-soft: #e8e2d4;
  --accent: #b8763d;
  --accent-hover: #a0682f;
  --accent-soft: #f3e6d4;
  --brand-bg: #1a3a32;
  --brand-bg-2: #102822;
  --brand-fg: #e8e0d0;
  --brand-fg-mute: rgba(232, 224, 208, 0.55);
  --font-serif: 'Songti SC', 'STSong', 'Source Han Serif SC', 'Noto Serif CJK SC', 'SimSun', serif;

  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: var(--paper);
  font-family: 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Segoe UI', sans-serif;
}

/* ============ 左侧边栏 ============ */
.app-sidebar {
  width: 232px;
  flex-shrink: 0;
  background: linear-gradient(180deg, var(--brand-bg) 0%, var(--brand-bg-2) 100%);
  color: var(--brand-fg);
  display: flex;
  flex-direction: column;
  transition: width 0.28s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  z-index: 10;
  box-shadow: 1px 0 0 rgba(0, 0, 0, 0.05);
}

.app-layout.collapsed .app-sidebar {
  width: 60px;
}

/* 侧栏顶部：品牌 + 折叠按钮 */
.sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 16px 16px;
  flex-shrink: 0;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  overflow: hidden;
}

.brand-mark {
  color: var(--brand-fg);
  flex-shrink: 0;
  opacity: 0.92;
}

.brand-name {
  font-family: var(--font-serif);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 4px;
  color: var(--brand-fg);
  white-space: nowrap;
  transition: opacity 0.2s;
}

.app-layout.collapsed .brand-name {
  opacity: 0;
  width: 0;
  overflow: hidden;
}

.collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: rgba(255, 255, 255, 0.08);
  border: none;
  border-radius: 6px;
  color: var(--brand-fg-mute);
  cursor: pointer;
  font-size: 15px;
  transition: all 0.2s;
  flex-shrink: 0;
}

.collapse-btn:hover {
  background: rgba(255, 255, 255, 0.16);
  color: var(--brand-fg);
}

.app-layout.collapsed .sidebar-head {
  justify-content: center;
  padding: 18px 8px 16px;
}

.app-layout.collapsed .collapse-btn {
  position: absolute;
  top: 22px;
  right: -14px;
  background: var(--brand-bg);
  border: 1px solid rgba(232, 224, 208, 0.18);
  z-index: 20;
}

/* 导航分组：固定高度，不抢占历史区空间；内容多时内部滚动 */
.sidebar-nav {
  flex-shrink: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px 0 16px;
  max-height: 50vh;
}

.sidebar-nav::-webkit-scrollbar {
  width: 4px;
}

.sidebar-nav::-webkit-scrollbar-thumb {
  background: rgba(232, 224, 208, 0.15);
  border-radius: 2px;
}

.nav-group {
  margin-bottom: 4px;
}

.group-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px 6px;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 3px;
  color: var(--brand-fg-mute);
  text-transform: uppercase;
  white-space: nowrap;
  user-select: none;
}

.group-label.clickable {
  cursor: pointer;
  transition: color 0.15s;
}

.group-label.clickable:hover {
  color: var(--brand-fg);
}

.group-chevron {
  font-size: 11px;
  opacity: 0.7;
  transition: transform 0.2s;
}

.nav-items {
  overflow: hidden;
}

.app-layout.collapsed .group-label {
  display: none;
}

/* 导航项 */
.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  text-decoration: none;
  color: rgba(232, 224, 208, 0.78);
  font-size: 14px;
  transition: all 0.18s ease;
  border-left: 2px solid transparent;
  margin: 1px 0;
  white-space: nowrap;
  position: relative;
}

.nav-icon {
  font-size: 17px;
  flex-shrink: 0;
  transition: color 0.18s;
}

.nav-text {
  transition: opacity 0.2s;
}

.app-layout.collapsed .nav-item {
  justify-content: center;
  padding: 12px 0;
  gap: 0;
}

.app-layout.collapsed .nav-text {
  opacity: 0;
  width: 0;
  overflow: hidden;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--brand-fg);
}

.nav-item:hover .nav-icon {
  color: var(--brand-fg);
}

.nav-item.active {
  background: rgba(184, 118, 61, 0.14);
  color: var(--accent);
  border-left-color: var(--accent);
}

.nav-item.active .nav-icon {
  color: var(--accent);
}

/* ============ 对话历史（KIMI 风格，吃剩余空间） ============ */
.chat-history {
  flex: 1;
  padding: 0 12px 4px;
  border-top: 1px solid rgba(232, 224, 208, 0.1);
  display: flex;
  flex-direction: column;
  min-height: 80px;
  overflow: hidden;
}

.history-header {
  flex-shrink: 0;
  padding: 10px 4px 6px;
  display: flex;
  align-items: center;
}

.history-label {
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 3px;
  color: var(--brand-fg-mute);
  text-transform: uppercase;
}

.app-layout.collapsed .history-header {
  display: none;
}

.history-list {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  margin: 0 -4px;
  padding: 0 4px 4px;
}

.history-list::-webkit-scrollbar {
  width: 4px;
}

.history-list::-webkit-scrollbar-thumb {
  background: rgba(232, 224, 208, 0.15);
  border-radius: 2px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  color: rgba(232, 224, 208, 0.72);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
  margin-bottom: 2px;
  white-space: nowrap;
  overflow: hidden;
}

.history-icon {
  font-size: 14px;
  flex-shrink: 0;
  opacity: 0.6;
}

.history-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 更多操作按钮：复用旧删除按钮的 hover 显隐模式，图标换为 MoreFilled */
.history-more {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: none;
  border: none;
  border-radius: 4px;
  color: var(--brand-fg-mute);
  cursor: pointer;
  font-size: 13px;
  flex-shrink: 0;
  opacity: 0;
  transition: all 0.15s;
}

.history-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--brand-fg);
}

.history-item:hover .history-more {
  opacity: 0.7;
}

.history-more:hover {
  background: rgba(255, 255, 255, 0.12);
  color: var(--brand-fg);
  opacity: 1 !important;
}

/* 就地编辑 input：撑满标题区，继承字号，避免条目跳动 */
.history-title-input {
  flex: 1;
  min-width: 0;
  background: rgba(255, 255, 255, 0.08);
  border: none;
  border-bottom: 1px solid var(--accent);
  border-radius: 2px;
  outline: none;
  color: var(--brand-fg);
  font-size: 13px;
  font-family: inherit;
  padding: 2px 4px;
  margin: 0;
}

.history-title-input::placeholder {
  color: var(--brand-fg-mute);
}

/* 编辑态：条目本身不再表现为可点击跳转，避免失焦误触 */
.history-item.editing {
  cursor: default;
  background: rgba(255, 255, 255, 0.04);
}

.history-item.active {
  background: rgba(184, 118, 61, 0.14);
  color: var(--accent);
}

.history-item.active .history-icon {
  opacity: 1;
  color: var(--accent);
}

.history-empty {
  padding: 12px 10px;
  font-size: 12px;
  color: var(--brand-fg-mute);
  text-align: center;
}

/* 侧栏底部：用户 + 退出 */
.sidebar-foot {
  flex-shrink: 0;
  padding: 12px 16px 16px;
  border-top: 1px solid rgba(232, 224, 208, 0.1);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px;
  color: var(--brand-fg);
  font-size: 13px;
  min-width: 0;
  overflow: hidden;
}

.user-avatar {
  font-size: 16px;
  color: var(--brand-fg-mute);
  background: rgba(255, 255, 255, 0.08);
  border-radius: 50%;
  padding: 6px;
  width: 16px;
  height: 16px;
  box-sizing: content-box;
  flex-shrink: 0;
}

.username {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: opacity 0.2s;
}

.app-layout.collapsed .user-info {
  justify-content: center;
  padding: 8px 0;
}

.app-layout.collapsed .username {
  opacity: 0;
  width: 0;
  overflow: hidden;
}

.logout-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px;
  background: none;
  border: none;
  border-radius: 6px;
  color: var(--brand-fg-mute);
  cursor: pointer;
  font-size: 13px;
  font-family: inherit;
  transition: all 0.18s;
  width: 100%;
  text-align: left;
}

.logout-btn:hover {
  background: rgba(176, 58, 46, 0.18);
  color: #e8a89d;
}

.app-layout.collapsed .logout-btn {
  justify-content: center;
  padding: 8px 0;
}

.app-layout.collapsed .logout-text {
  opacity: 0;
  width: 0;
  overflow: hidden;
}

/* ============ 右侧主区域 ============ */
.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  background: var(--paper);
}

.breadcrumb-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 28px;
  background: var(--paper);
  border-bottom: 1px solid var(--line-soft);
  font-size: 13px;
  color: var(--ink-mute);
  flex-shrink: 0;
}

.breadcrumb-home {
  font-size: 15px;
  color: var(--accent);
}

.breadcrumb-sep {
  color: var(--line);
}

.breadcrumb-current {
  color: var(--ink);
  font-weight: 500;
  letter-spacing: 0.5px;
}

.main-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: var(--paper);
}

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .app-sidebar {
    width: 60px;
  }

  .brand-name,
  .nav-text,
  .group-label,
  .username,
  .logout-text {
    opacity: 0;
    width: 0;
    overflow: hidden;
  }

  .sidebar-head,
  .nav-item,
  .user-info,
  .logout-btn {
    justify-content: center;
    padding-left: 0;
    padding-right: 0;
  }

  .collapse-btn {
    display: none;
  }
}
</style>
