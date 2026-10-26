import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  // HR 后台：默认落在候选人看板，HR 打开就能看到分数排行
  { path: '/', redirect: '/board' },
  {
    path: '/board/:jobId?',
    name: 'board',
    component: () => import('../views/CandidateBoard.vue'),
    meta: { title: '候选人看板' }
  },
  {
    path: '/jobs',
    name: 'jobs',
    component: () => import('../views/JobList.vue'),
    meta: { title: '岗位管理' }
  },
  {
    path: '/resumes',
    name: 'resumes',
    component: () => import('../views/ResumeUpload.vue'),
    meta: { title: '简历库' }
  },
  {
    path: '/applications/:id',
    name: 'application',
    component: () => import('../views/ApplicationDetail.vue'),
    meta: { title: '匹配详情' }
  },

  // 求职者侧：独立布局，没有后台导航
  {
    path: '/apply/:jobId',
    name: 'apply',
    component: () => import('../views/ApplyPage.vue'),
    meta: { title: '投递简历', public: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 简历智能筛选` : '简历智能筛选与岗位匹配系统'
})

export default router
