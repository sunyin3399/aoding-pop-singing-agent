import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import ChatView from '../views/ChatView.vue'

const SpectrumLabView = () => import('../views/SpectrumLabView.vue')
const TrainingPlanView = () => import('../views/TrainingPlanView.vue')
const KnowledgeReviewView = () => import('../views/KnowledgeReviewView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/coach', name: 'coach', component: ChatView, props: { mode: 'coach' } },
    { path: '/assistant', name: 'assistant', component: ChatView, props: { mode: 'assistant' } },
    { path: '/spectrum-lab', name: 'spectrum-lab', component: SpectrumLabView },
    { path: '/training-plan', name: 'training-plan', component: TrainingPlanView },
    { path: '/admin/knowledge-review', name: 'knowledge-review', component: KnowledgeReviewView },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

export default router
