<template>
  <!-- 求职者投递页：独立布局，不带后台导航 -->
  <router-view v-if="isPublic"/>

  <!-- HR 后台 -->
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">筛</div>
        <div class="brand-text">
          <b>简历智能筛选</b>
          <span>HR CONSOLE</span>
        </div>
      </div>

      <router-link
        v-for="item in navs"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item) }"
      >
        <el-icon><component :is="item.icon"/></el-icon>
        <span>{{ item.label }}</span>
      </router-link>

      <div class="spacer"/>

      <div class="foot">
        <div>Spring Boot · 8080</div>
        <div>FastAPI · 8000</div>
        <div>RocketMQ · 9876</div>
      </div>
    </aside>

    <main class="main">
      <router-view/>
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const navs = [
  { path: '/board', label: '候选人看板', icon: 'DataAnalysis' },
  { path: '/jobs', label: '岗位管理', icon: 'Briefcase' },
  { path: '/resumes', label: '简历库', icon: 'Files' }
]

const isPublic = computed(() => route.meta.public === true)
const current = computed(() => route.path)

function isActive(item) {
  if (item.path === '/board') {
    return current.value.startsWith('/board') || current.value.startsWith('/applications')
  }
  return current.value.startsWith(item.path)
}
</script>
