<template>
  <div class="page-head">
    <div>
      <h1>候选人看板</h1>
      <p>求职者从官网投递后自动进入这里，按综合分倒序；分数可拆成规则分与语义分</p>
    </div>
    <div style="display: flex; gap: 10px; align-items: center">
      <el-select v-model="jobId" style="width: 240px" placeholder="选择岗位" @change="onJobChange">
        <el-option
          v-for="j in jobs"
          :key="j.id"
          :label="`${j.title}${j.department ? ' · ' + j.department : ''}`"
          :value="j.id"
        />
      </el-select>
      <el-button :icon="Link" :disabled="!jobId" @click="copyApplyLink">投递链接</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>
  </div>

  <div class="page-body">
    <template v-if="!jobId">
      <div class="panel empty">先选一个岗位，或去「岗位管理」创建岗位</div>
    </template>

    <template v-else>
      <div class="stat-strip">
        <div class="stat-card">
          <div class="k">投递总数</div>
          <div class="v">{{ total }}</div>
        </div>
        <div class="stat-card">
          <div class="k">已打分</div>
          <div class="v">{{ scoredCount }}</div>
        </div>
        <div class="stat-card">
          <div class="k">最高分</div>
          <div class="v score-high">{{ maxScore != null ? maxScore.toFixed(1) : '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">平均分</div>
          <div class="v">{{ avgScore != null ? avgScore.toFixed(1) : '—' }}</div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-title">
          <h3>候选人排名</h3>
          <div style="display: flex; gap: 10px; align-items: center">
            <el-input-number
              v-model="minScore"
              :min="0"
              :max="100"
              :step="5"
              size="small"
              controls-position="right"
              style="width: 130px"
              placeholder="最低分"
              @change="load"
            />
            <el-select v-model="degree" size="small" style="width: 120px" clearable
                       placeholder="学历" @change="load">
              <el-option v-for="d in ['大专', '本科', '硕士', '博士']" :key="d" :label="d" :value="d"/>
            </el-select>
            <span v-if="topSource" class="skill-chip plus" style="font-size: 11px">
              TOP20 走 {{ topSource === 'redis' ? 'Redis 排行榜' : 'MySQL 重建' }}
            </span>
          </div>
        </div>

        <el-table
          :data="records"
          v-loading="loading"
          stripe
          style="width: 100%"
          :row-style="{ cursor: 'pointer' }"
          @row-click="(row) => goDetail(row)"
        >
          <el-table-column label="#" width="54" align="center">
            <template #default="{ $index }">
              <span :class="rankClass($index)" style="font-weight: 700">{{ $index + 1 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="候选人" min-width="132">
            <template #default="{ row }">
              <span style="font-weight: 600">{{ row.candidateName || '未识别' }}</span>
              <div class="muted" style="font-size: 11.5px; margin-top: 2px">
                <span v-if="row.matchLevel === 'NONE'" class="lv-tag lv-none">方向不符</span>
                <span v-else-if="row.matchLevel === 'PARTIAL'" class="lv-tag lv-partial">部分相关</span>
                {{ row.phone || row.email || '' }}
              </div>
              <div v-if="row.status === 'FAILED'" style="font-size: 11.5px; color: #c0392b">打分失败</div>
              <div v-else-if="row.status === 'WAITING'" class="muted" style="font-size: 11.5px">待简历解析</div>
              <div v-else-if="row.status === 'SCORING'" class="muted" style="font-size: 11.5px">打分中…</div>
            </template>
          </el-table-column>

          <el-table-column label="学历" width="72">
            <template #default="{ row }">{{ row.degree || '—' }}</template>
          </el-table-column>

          <el-table-column label="年限" width="76">
            <template #default="{ row }">
              {{ row.totalYears != null ? row.totalYears + ' 年' : '—' }}
            </template>
          </el-table-column>

          <el-table-column label="综合分" width="196">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 8px">
                <span class="score-num" :class="scoreClass(row.score)" style="font-size: 17px; width: 48px">
                  {{ row.score != null ? Number(row.score).toFixed(1) : '—' }}
                </span>
                <el-progress
                  :percentage="Number(row.score) || 0"
                  :stroke-width="6"
                  :show-text="false"
                  :color="barColor(row.score)"
                  style="flex: 1"
                />
              </div>
              <div class="muted" style="font-size: 11px; margin-top: 2px; white-space: nowrap">
                规则 {{ row.ruleScore ?? '—' }} × 0.6 + 语义 {{ row.semanticScore ?? '—' }} × 0.4
              </div>
            </template>
          </el-table-column>

          <el-table-column label="技能命中" width="98">
            <template #default="{ row }">
              <span class="score-num">{{ row.hitCount }}/{{ row.mustCount }}</span>
              <div style="margin-top: 3px; font-size: 11px; white-space: nowrap; overflow: hidden;
                          text-overflow: ellipsis; color: #c0392b">
                {{ (row.missSkills || []).length ? '缺 ' + row.missSkills.join('、') : '' }}
              </div>
            </template>
          </el-table-column>

          <el-table-column label="推荐理由" min-width="230" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="muted" style="font-size: 12.5px">
                {{ row.reason || (row.status === 'SCORING' ? '打分中…' : '—') }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="!loading && !records.length" class="empty">
          这个岗位还没有候选人。去「简历管理」上传并投递，或在简历列表点“投递”
        </div>

        <div v-else style="margin-top: 10px; font-size: 12px" class="muted">
          点击任意一行查看分数拆解与完整档案
        </div>

        <div v-if="total > pageSize" style="margin-top: 14px; text-align: right">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next"
            @current-change="load"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Link } from '@element-plus/icons-vue'
import { jobApi } from '../api'
import { useJobStore } from '../stores/job'

const route = useRoute()
const router = useRouter()
const jobStore = useJobStore()

const jobId = ref(null)
const records = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)
const minScore = ref(null)
const degree = ref('')
const topSource = ref('')

const jobs = computed(() => jobStore.jobs)

const scoredCount = computed(() => records.value.filter((r) => r.status === 'SCORED').length)
const scores = computed(() => records.value.filter((r) => r.score != null).map((r) => Number(r.score)))
const maxScore = computed(() => (scores.value.length ? Math.max(...scores.value) : null))
const avgScore = computed(() =>
  scores.value.length ? scores.value.reduce((a, b) => a + b, 0) / scores.value.length : null
)

onMounted(async () => {
  await jobStore.fetchList()
  const fromRoute = route.params.jobId
  jobId.value = fromRoute ? Number(fromRoute) : jobStore.currentJobId || jobs.value[0]?.id || null
  if (jobId.value) {
    jobStore.setCurrent(jobId.value)
    await load()
  }
})

watch(
  () => route.params.jobId,
  (v) => {
    if (v && Number(v) !== jobId.value) {
      jobId.value = Number(v)
      page.value = 1
      load()
    }
  }
)

function onJobChange() {
  jobStore.setCurrent(jobId.value)
  page.value = 1
  router.replace(`/board/${jobId.value}`)
  load()
}

async function load() {
  if (!jobId.value) return
  loading.value = true
  try {
    const data = await jobApi.board(jobId.value, {
      page: page.value,
      size: pageSize,
      minScore: minScore.value ?? undefined,
      degree: degree.value || undefined
    })
    records.value = data.records || []
    total.value = data.total || 0
    loadTopTag()
  } finally {
    loading.value = false
  }
}

/** TOP 通道只是展示用的旁路，用来验证 Redis 排行榜确实在工作 */
async function loadTopTag() {
  try {
    const top = await jobApi.top(jobId.value, 1)
    topSource.value = top?.[0]?.source || ''
  } catch (e) {
    topSource.value = ''
  }
}

function scoreClass(s) {
  if (s == null) return 'muted'
  if (s >= 80) return 'score-high'
  if (s >= 60) return 'score-mid'
  if (s >= 40) return 'score-low'
  return 'score-bad'
}

function barColor(s) {
  if (s == null) return '#cbd5e1'
  if (s >= 80) return '#16a34a'
  if (s >= 60) return '#2f6bff'
  if (s >= 40) return '#d97706'
  return '#dc2626'
}

function rankClass(i) {
  return i === 0 ? 'score-mid' : 'muted'
}

function goDetail(row) {
  router.push(`/applications/${row.applicationId}`)
}

/** 当前岗位的求职者投递页地址 */
async function copyApplyLink() {
  if (!jobId.value) return
  const url = `${window.location.origin}/apply/${jobId.value}`
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success(`已复制：${url}`)
  } catch (e) {
    ElMessageBox.alert(url, '投递链接（手动复制）', { confirmButtonText: '知道了' })
  }
}
</script>
