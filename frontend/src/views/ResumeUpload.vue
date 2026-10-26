<template>
  <div class="page-head">
    <div>
      <h1>简历库</h1>
      <p>求职者从官网投递进来的简历都在这里，跟踪解析状态；解析失败的可重试</p>
    </div>
    <div>
      <el-radio-group v-model="statusFilter" size="default" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="EXTRACTED">已解析</el-radio-button>
        <el-radio-button value="PARSING">处理中</el-radio-button>
        <el-radio-button value="FAILED">失败</el-radio-button>
      </el-radio-group>
    </div>
  </div>

  <div class="page-body">
    <!-- 线下简历导入：官网投递不经过这里，这条通道只给内推 / 邮件附件用 -->
    <div class="panel">
      <div class="panel-title" style="margin-bottom: 10px">
        <h3>线下简历导入</h3>
        <span class="muted" style="font-size: 12px">
          内推、邮件附件、招聘会收到的简历从这里进；官网投递的会自动出现在下方列表
        </span>
      </div>

      <el-upload
        drag
        multiple
        :auto-upload="false"
        :show-file-list="false"
        accept="application/pdf"
        :on-change="onFileChange"
      >
        <el-icon style="font-size: 34px; color: #b6c0d0"><UploadFilled/></el-icon>
        <div style="margin-top: 8px; font-size: 13.5px">
          拖入 PDF 或<em style="color: var(--accent); font-style: normal">点击选择</em>
        </div>
        <div class="muted" style="font-size: 12px; margin-top: 6px">
          仅支持文本型 PDF，单份 ≤ 10MB，一次最多 20 份；同一文件按内容哈希去重
        </div>
      </el-upload>

      <div v-if="uploading" style="margin-top: 12px">
        <el-progress :percentage="100" :indeterminate="true" :show-text="false" :stroke-width="4"/>
        <div class="muted" style="font-size: 12px; margin-top: 6px">正在上传…</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">
        <h3>全部简历 <span class="muted" style="font-weight: 400">共 {{ total }} 份</span></h3>
        <div style="display: flex; align-items: center; gap: 10px">
          <span v-if="polling" class="muted" style="font-size: 12px">
            <el-icon class="is-loading" style="vertical-align: -2px"><Loading/></el-icon>
            正在跟踪解析状态…
          </span>
          <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <el-table :data="list" v-loading="loading" stripe style="width: 100%">
        <el-table-column label="候选人" width="120">
          <template #default="{ row }">
            <span v-if="row.candidateName" style="font-weight: 600">{{ row.candidateName }}</span>
            <span v-else-if="row.status === 'FAILED'" class="muted">未识别</span>
            <span v-else class="muted">解析中…</span>
            <div class="muted" style="font-size: 11.5px; margin-top: 2px">
              {{ row.phone || row.email || '' }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="文件" min-width="180">
          <template #default="{ row }">
            <div style="font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
              {{ row.fileName }}
            </div>
            <div class="muted" style="font-size: 12px">
              #{{ row.id }} · {{ row.createdAt?.replace('T', ' ') }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="投递岗位" min-width="150">
          <template #default="{ row }">
            <span v-if="row.appliedJobs" style="font-size: 12.5px">{{ row.appliedJobs }}</span>
            <span v-else class="muted" style="font-size: 12.5px">未投递</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light" round size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
            <div v-if="row.errorMsg" style="font-size: 11.5px; color: #c0392b; margin-top: 4px; line-height: 1.5">
              {{ row.errorMsg }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="耗时" width="72" align="center">
          <template #default="{ row }">
            <span v-if="row.costMs != null">{{ (row.costMs / 1000).toFixed(1) }}s</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.status !== 'EXTRACTED'"
                       @click="openProfile(row)">档案
            </el-button>
            <el-button link type="primary" :disabled="row.status !== 'EXTRACTED'"
                       @click="openAssign(row)">指派岗位
            </el-button>
            <el-button link type="warning" :disabled="row.status !== 'FAILED'"
                       @click="retry(row)">重新解析
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && !list.length" class="empty">
        还没有简历。把投递链接发给求职者，或者从上面导入线下简历
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
  </div>

  <!-- 档案抽屉 -->
  <el-drawer v-model="profileVisible" size="620px" :title="`简历档案 · ${current?.candidateName || ''}`">
    <div v-if="detailLoading" class="empty">读取中…</div>
    <div v-else-if="detail?.profile">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="姓名">{{ detail.profile.name || '未抽取到' }}</el-descriptions-item>
        <el-descriptions-item label="学历">
          {{ detail.profile.education?.degree || '未抽取到' }}
        </el-descriptions-item>
        <el-descriptions-item label="毕业院校">
          {{ detail.profile.education?.school || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="专业">{{ detail.profile.education?.major || '—' }}</el-descriptions-item>
        <el-descriptions-item label="工作年限">
          <b>{{ detail.profile.total_years ?? '未抽取到' }}</b>
          <span v-if="detail.profile.total_years != null"> 年（不含实习）</span>
        </el-descriptions-item>
        <el-descriptions-item label="毕业年份">
          {{ detail.profile.education?.grad_year || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="电话">{{ detail.profile.phone || detail.phone || '—' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ detail.profile.email || detail.email || '—' }}</el-descriptions-item>
      </el-descriptions>

      <h4 style="margin: 20px 0 10px; font-size: 14px">技能（已归一化）</h4>
      <div class="chip-row">
        <span v-for="s in detail.profile.skills" :key="s" class="skill-chip">{{ s }}</span>
        <span v-if="!detail.profile.skills?.length" class="muted">未抽取到技能</span>
      </div>

      <h4 style="margin: 20px 0 10px; font-size: 14px">人物画像</h4>
      <div style="line-height: 1.8">{{ detail.profile.summary || '—' }}</div>

      <h4 style="margin: 20px 0 10px; font-size: 14px">工作经历</h4>
      <el-timeline v-if="detail.profile.experiences?.length">
        <el-timeline-item
          v-for="(e, i) in detail.profile.experiences"
          :key="i"
          :timestamp="e.period || ''"
          placement="top"
        >
          <div style="font-weight: 600">{{ e.company }} · {{ e.title }}</div>
          <div class="muted" style="margin-top: 4px; line-height: 1.7">{{ e.summary }}</div>
        </el-timeline-item>
      </el-timeline>
      <div v-else class="muted">未抽取到工作经历</div>
    </div>
    <div v-else class="empty">暂无档案</div>
  </el-drawer>

  <!-- 指派岗位（线下简历） -->
  <el-dialog v-model="assignVisible" title="指派到岗位" width="480px">
    <div class="muted" style="margin-bottom: 12px">
      给 <b>{{ current?.candidateName }}</b> 指派岗位后会立即打分，出现在该岗位的候选人看板里。
      官网投递的候选人不需要这一步。
    </div>
    <el-select v-model="targetJobId" placeholder="选择岗位" style="width: 100%">
      <el-option
        v-for="j in openJobs"
        :key="j.id"
        :label="`${j.title}${j.department ? ' · ' + j.department : ''}`"
        :value="j.id"
      />
    </el-select>
    <div v-if="!openJobs.length" class="muted" style="margin-top: 10px; font-size: 13px">
      还没有开放的岗位，先去「岗位管理」建一个
    </div>
    <template #footer>
      <el-button @click="assignVisible = false">取消</el-button>
      <el-button type="primary" :disabled="!targetJobId" :loading="assigning" @click="doAssign">
        指派并打分
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Loading } from '@element-plus/icons-vue'
import { applicationApi, resumeApi } from '../api'
import { useJobStore } from '../stores/job'

const router = useRouter()
const jobStore = useJobStore()

const list = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const uploading = ref(false)
const statusFilter = ref('')
const polling = ref(false)

const profileVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const current = ref(null)

const assignVisible = ref(false)
const targetJobId = ref(null)
const assigning = ref(false)

const openJobs = computed(() => jobStore.openJobs)

let timer = null
let pollCount = 0

onMounted(async () => {
  await jobStore.fetchList()
  await load()
})

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})

async function load() {
  loading.value = true
  try {
    const data = await resumeApi.list({
      page: page.value,
      size: pageSize,
      status: statusFilter.value || undefined
    })
    list.value = data.records || []
    total.value = data.total || 0
    schedulePoll()
  } finally {
    loading.value = false
  }
}

/** 有 PENDING/PARSING 就持续跟踪，全部完成后自动停 */
function schedulePoll() {
  if (timer) clearTimeout(timer)
  const pending = list.value.filter((r) => r.status === 'PENDING' || r.status === 'PARSING')
  if (!pending.length || pollCount > 60) {
    polling.value = false
    pollCount = 0
    return
  }
  polling.value = true
  timer = setTimeout(async () => {
    pollCount += 1
    try {
      await Promise.allSettled(pending.map((r) => resumeApi.status(r.id)))
      const data = await resumeApi.list({
        page: page.value,
        size: pageSize,
        status: statusFilter.value || undefined
      })
      list.value = data.records || []
      total.value = data.total || 0
    } catch (e) {
      // 轮询失败不打断页面，下一轮继续
    }
    schedulePoll()
  }, 2000)
}

const pendingFiles = []
let pendingTimer = null

async function onFileChange(uploadFile) {
  // el-upload 的 on-change 是逐文件触发的，这里做一次 100ms 去抖合并成一个请求
  pendingFiles.push(uploadFile.raw)
  if (pendingTimer) clearTimeout(pendingTimer)
  pendingTimer = setTimeout(() => flushUpload(), 120)
}

async function flushUpload() {
  const files = pendingFiles.splice(0, pendingFiles.length)
  if (!files.length) return

  const pdfs = files.filter((f) => f.name.toLowerCase().endsWith('.pdf'))
  if (pdfs.length !== files.length) {
    ElMessage.warning('已忽略非 PDF 文件')
  }
  if (!pdfs.length) return
  if (pdfs.length > 20) {
    ElMessage.error('单次最多 20 份')
    return
  }

  uploading.value = true
  try {
    if (pdfs.length === 1) {
      const r = await resumeApi.upload(pdfs[0])
      ElMessage.success(r.duplicated ? `已存在同一份简历（#${r.resumeId}），未重复解析` : '上传成功，正在解析')
    } else {
      const results = await resumeApi.batchUpload(pdfs)
      const ok = results.filter((r) => !r.error).length
      ElMessage.success(`已提交 ${ok}/${results.length} 份，解析中`)
      const bad = results.filter((r) => r.error)
      if (bad.length) {
        ElMessage.warning(`${bad.length} 份失败：${bad[0].fileName} — ${bad[0].error}`)
      }
    }
    pollCount = 0
    page.value = 1
    await load()
  } finally {
    uploading.value = false
  }
}

function statusType(s) {
  return { PENDING: 'info', PARSING: 'warning', EXTRACTED: 'success', FAILED: 'danger' }[s] || 'info'
}

function statusLabel(s) {
  return { PENDING: '待处理', PARSING: '解析中', EXTRACTED: '已解析', FAILED: '失败' }[s] || s
}

async function openProfile(row) {
  current.value = row
  detail.value = null
  detailLoading.value = true
  profileVisible.value = true
  try {
    detail.value = await resumeApi.detail(row.id)
  } finally {
    detailLoading.value = false
  }
}

async function retry(row) {
  await resumeApi.retry(row.id)
  ElMessage.success('已重新投递解析任务')
  pollCount = 0
  await load()
}

function openAssign(row) {
  current.value = row
  targetJobId.value = jobStore.currentJobId || (openJobs.value[0]?.id ?? null)
  assignVisible.value = true
}

async function doAssign() {
  assigning.value = true
  try {
    const r = await applicationApi.apply(targetJobId.value, current.value.id)
    assignVisible.value = false
    ElMessage.success('已指派，打分中')
    router.push(`/applications/${r.applicationId}`)
  } finally {
    assigning.value = false
  }
}
</script>
