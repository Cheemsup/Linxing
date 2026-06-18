import { createRouter, createWebHistory } from 'vue-router'
import { authStore } from '@/stores/authStore'
import LoginView from '@/views/auth/LoginView.vue'
import SearchView from '@/views/rag/SearchView.vue'
import ChatView from '@/views/rag/ChatView.vue'
import IngestView from '@/views/rag/IngestView.vue'
import NotesView from '@/views/rag/NotesView.vue'
import QuizView from '@/views/rag/QuizView.vue'
import StudyPlanView from '@/views/rag/StudyPlanView.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: {
      title: '用户登录',
      requiresAuth: false
    }
  },
  {
    path: '/register',
    redirect: '/login'
  },
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/search',
    name: 'Search',
    component: SearchView,
    meta: {
      title: '知识库搜索',
      requiresAuth: true
    }
  },
  {
    path: '/chat',
    name: 'Chat',
    component: ChatView,
    meta: {
      title: '智能问答',
      requiresAuth: true
    }
  },
  {
    path: '/ingest',
    name: 'Ingest',
    component: IngestView,
    meta: {
      title: '导入笔记',
      requiresAuth: true
    }
  },
  {
    path: '/notes',
    name: 'Notes',
    component: NotesView,
    meta: {
      title: '笔记管理',
      requiresAuth: true
    }
  },
  {
    path: '/quiz',
    name: 'Quiz',
    component: QuizView,
    meta: {
      title: '知识测验',
      requiresAuth: true
    }
  },
  {
    path: '/study-plan',
    name: 'StudyPlan',
    component: StudyPlanView,
    meta: {
      title: '学习计划',
      requiresAuth: true
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - Personal Note RAG` : 'Personal Note RAG'

  const isAuthenticated = authStore.isAuthenticated()

  if (to.meta.requiresAuth && !isAuthenticated) {
    next('/login')
  } else if ((to.path === '/login' || to.path === '/register') && isAuthenticated) {
    next('/search')
  } else {
    next()
  }
})

export default router
