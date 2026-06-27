import { createRouter, createWebHistory } from 'vue-router'
import { authStore } from '@/stores/authStore'
import AppLayout from '@/layouts/AppLayout.vue'
import LoginView from '@/views/auth/LoginView.vue'

// 路由懒加载，减少首屏体积
const SearchView = () => import('@/views/agent/SearchView.vue')
const ChatView = () => import('@/views/agent/ChatView.vue')
const IngestView = () => import('@/views/agent/IngestView.vue')
const NotesView = () => import('@/views/agent/NotesView.vue')
const ExamListView = () => import('@/views/agent/ExamListView.vue')
const ExamDetailView = () => import('@/views/agent/ExamDetailView.vue')
const PlanListView = () => import('@/views/agent/PlanListView.vue')
const PlanDetailView = () => import('@/views/agent/PlanDetailView.vue')

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
  // 主应用：统一由 AppLayout 包裹（顶部导航 + 内容区）
  {
    path: '/',
    component: AppLayout,
    meta: {
      requiresAuth: true
    },
    children: [
      { path: '', redirect: '/chat' },
      {
        path: 'search',
        name: 'Search',
        component: SearchView,
        meta: { title: '搜索笔记', requiresAuth: true }
      },
      {
        path: 'chat',
        name: 'Chat',
        component: ChatView,
        meta: { title: '智能问答', requiresAuth: true }
      },
      {
        path: 'ingest',
        name: 'Ingest',
        component: IngestView,
        meta: { title: '导入笔记', requiresAuth: true }
      },
      {
        path: 'notes',
        name: 'Notes',
        component: NotesView,
        meta: { title: '笔记管理', requiresAuth: true }
      },
      {
        path: 'quiz',
        name: 'Quiz',
        component: ExamListView,
        meta: { title: '知识测验', requiresAuth: true }
      },
      {
        path: 'quiz/:examId',
        name: 'ExamDetail',
        component: ExamDetailView,
        meta: { title: '测验作答', requiresAuth: true }
      },
      {
        path: 'study-plan',
        name: 'StudyPlan',
        component: PlanListView,
        meta: { title: '学习计划', requiresAuth: true }
      },
      {
        path: 'study-plan/:planId',
        name: 'StudyPlanDetail',
        component: PlanDetailView,
        meta: { title: '计划详情', requiresAuth: true }
      }
    ]
  },
  // 兜底
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - 临星` : '临星'

  const isAuthenticated = authStore.isAuthenticated()

  if (to.meta.requiresAuth && !isAuthenticated) {
    next('/login')
  } else if ((to.path === '/login' || to.path === '/register') && isAuthenticated) {
    next('/chat')
  } else {
    next()
  }
})

export default router
