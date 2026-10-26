import { defineStore } from 'pinia'
import { jobApi } from '../api'

/**
 * 岗位数据在三个页面都要用（岗位管理、看板筛选、详情回跳），
 * 放 Pinia 里缓存一份，避免每次都重新拉。
 */
export const useJobStore = defineStore('job', {
  state: () => ({
    jobs: [],
    loaded: false,
    loading: false,
    currentJobId: null
  }),

  getters: {
    openJobs: (state) => state.jobs.filter((j) => j.status === 1),
    jobById: (state) => (id) => state.jobs.find((j) => j.id === Number(id))
  },

  actions: {
    async fetchList(force = false) {
      if (this.loaded && !force) return this.jobs
      this.loading = true
      try {
        const page = await jobApi.list({ page: 1, size: 100 })
        this.jobs = page.records || []
        this.loaded = true
        if (!this.currentJobId && this.jobs.length) {
          this.currentJobId = this.jobs[0].id
        }
        return this.jobs
      } finally {
        this.loading = false
      }
    },

    setCurrent(id) {
      this.currentJobId = Number(id)
    }
  }
})
