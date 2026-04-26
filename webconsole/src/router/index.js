import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import IngestView from '@/views/IngestView.vue'
import NotesView from '@/views/NotesView.vue'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/chat',
    name: 'Chat',
    component: ChatView,
    meta: { title: '智能问答' }
  },
  {
    path: '/ingest',
    name: 'Ingest',
    component: IngestView,
    meta: { title: '导入笔记' }
  },
  {
    path: '/notes',
    name: 'Notes',
    component: NotesView,
    meta: { title: '笔记管理' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - Personal Note RAG` : 'Personal Note RAG'
  next()
})

export default router
