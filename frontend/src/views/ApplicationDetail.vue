<template>
  <div class="page-head">
    <div>
      <el-button link :icon="ArrowLeft" @click="goBack" style="padding-left: 0; margin-bottom: 4px">
        返回看板
      </el-button>
      <h1>
        {{ data?.candidateName || '候选人' }}
        <span class="muted" style="font-size: 15px; font-weight: 400">· {{ data?.jobTitle }}</span>
      </h1>
      <p>投递 #{{ data?.applicationId }} · {{ fmtTime(data?.createdAt) }}</p>
    </div>
    <div style="display: flex; gap: 10px; align-items: center">
      <el-tag v-if="data" :type="statusType(data.status)" effect="light" round>
        {{ statusLabel(data.status) }}
      </el-tag>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" plain :loading="rescore" :disabled="!data" @click="redo">
        重新打分
      </el-button>
    </div>
  </div>

  <div class="page-body">
    <div v-if="loading && !data" class="panel empty">加载中…</div>

    <div v-else-if="data" class="detail-grid">
      <div>
        <!-- 总分 -->
        <div class="panel score-hero">
          <div class="hero-left">
            <div class="k muted">综合分</div>
            <div class="big" :class="scoreClass(Number(data.score))">
              {{ data.score != null ? Number(data.score).toFixed(1) : '—' }}
            </div>
            <div class="muted" style="font-size: 12px; margin-top: 2px">
              规则分 × 0.6 + 语义分 × 0.4
            </div>
          </div>

          <div class="hero-right">
            <div class="formula mono">
              {{ breakdown.ruleScore }} × 0.6 + {{ data.breakdown?.semanticScore ?? '—' }} × 0.4 =
              {{ data.score != null ? Number(data.score).toFixed(2) : '—' }}
            </div>
            <div v-if="data.status === 'SCORING' || data.status === 'WAITING'" class="muted"
                 style="font-size: 12px; margin-top: 6px">
              <el-icon class="is-loading" style="vertical-align: -2px"><Loading/></el-icon>
              {{ data.status === 'WAITING' ? '简历还在解析，完成后会自动打分' : '打分任务在队列中' }}，页面会自动刷新
            </div>
            <div v-if="data.status === 'FAILED'" style="font-size: 12px; margin-top: 6px; color: #c0392b">
              {{ data.errorMsg || '打分失败' }}
            </div>
          </div>
        </div>

        <!-- 分数拆解 -->
        <div class="panel">
          <div class="panel-title">
            <h3>分数拆解</h3>
            <span class="muted" style="font-size: 12px">规则分由 Java 按确定性公式计算，语义分由 LLM 给出</span>
          </div>

          <div class="break-row" v-for="item in breakdownItems" :key="item.label">
            <div class="break-label">{{ item.label }}</div>
            <div class="break-bar">
              <el-progress
                :percentage="item.percent"
                :stroke-width="10"
                :show-text="false"
                :color="item.color"
              />
            </div>
            <div class="break-value">
              <b>{{ item.value }}</b><span class="muted"> / {{ item.max }}</span>
            </div>
            <div class="break-note muted">{{ item.note }}</div>
          </div>

          <el-divider style="margin: 14px 0"/>

          <div class="break-row">
            <div class="break-label"><b>规则分合计</b></div>
            <div class="break-bar">
              <el-progress :percentage="breakdown.ruleScore" :stroke-width="10" :show-text="false"
                           color="#2f6bff"/>
            </div>
            <div class="break-value"><b>{{ breakdown.ruleScore }}</b><span class="muted"> / 100</span></div>
            <div class="break-note muted">三项相加，确定性可复现</div>
          </div>

          <div class="break-row">
            <div class="break-label"><b>语义分</b></div>
            <div class="break-bar">
              <el-progress :percentage="Number(data.breakdown?.semanticScore) || 0" :stroke-width="10"
                           :show-text="false" color="#7c3aed"/>
            </div>
            <div class="break-value">
              <b>{{ data.breakdown?.semanticScore ?? '—' }}</b><span class="muted"> / 100</span>
            </div>
            <div class="break-note muted">只看经历与岗位的契合度，不重复扣学历年限</div>
          </div>
        </div>

        <!-- 技能 -->
        <div class="panel">
          <div class="panel-title">
            <h3>必备技能比对</h3>
            <span class="muted" style="font-size: 12px">
              命中 {{ (data.hitSkills || []).length }} / {{ breakdown.skillTotal }} 项
            </span>
          </div>

          <div style="margin-bottom: 12px">
            <div class="muted" style="font-size: 12px; margin-bottom: 6px">命中</div>
            <div class="chip-row">
              <span v-for="s in data.hitSkills" :key="s" class="skill-chip">
                <el-icon><Check/></el-icon>{{ s }}
              </span>
              <span v-if="!data.hitSkills?.length" class="muted">无</span>
            </div>
          </div>

          <div>
            <div class="muted" style="font-size: 12px; margin-bottom: 6px">缺失</div>
            <div class="chip-row">
              <span v-for="s in data.missSkills" :key="s" class="skill-chip miss">
                <el-icon><Close/></el-icon>{{ s }}
              </span>
              <span v-if="!data.missSkills?.length" class="muted">必备技能全部命中</span>
            </div>
          </div>
        </div>

        <!-- 推荐理由 -->
        <div class="panel">
          <div class="panel-title">
            <h3>推荐理由</h3>
            <span class="muted" style="font-size: 12px">LLM 生成，≤120 字</span>
          </div>
          <div class="reason-box">
            {{ data.reason || '暂未生成（打分未完成）' }}
          </div>
        </div>
      </div>

      <!-- 右侧档案 -->
      <div>
        <div class="panel">
          <div class="panel-title"><h3>候选人档案</h3></div>
          <template v-if="data.profile">
            <div class="kv" v-for="kv in profileKv" :key="kv.k">
              <span class="muted">{{ kv.k }}</span>
              <span>{{ kv.v }}</span>
            </div>

            <h4 style="margin: 16px 0 8px; font-size: 13px">技能清单</h4>
            <div class="chip-row">
              <span v-for="s in data.profile.skills" :key="s" class="skill-chip">{{ s }}</span>
              <span v-if="!data.profile.skills?.length" class="muted">—</span>
            </div>

            <h4 style="margin: 16px 0 8px; font-size: 13px">人物画像</h4>
            <div class="muted" style="line-height: 1.75; font-size: 13px">
              {{ data.profile.summary || '—' }}
            </div>

            <h4 style="margin: 16px 0 8px; font-size: 13px">工作经历</h4>
            <el-timeline v-if="data.profile.experiences?.length">
              <el-timeline-item
                v-for="(e, i) in data.profile.experiences"
                :key="i"
                :timestamp="e.period || ''"
                placement="top"
                size="normal"
              >
                <div style="font-weight: 600; font-size: 13px">{{ e.company }}</div>
                <div class="muted" style="font-size: 12px">{{ e.title }}</div>
                <div class="muted" style="font-size: 12px; line-height: 1.65; margin-top: 4px">
                  {{ e.summary }}
                </div>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="muted">—</div>
          </template>
          <div v-else class="empty">档案缺失</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Refresh, Loading, Check, Close } from '@element-plus/icons-vue'
import { applicationApi } from '../api'

const route = useRoute()
const router = useRouter()

const data = ref(null)
const loading = ref(false)
const rescore = ref(false)
let timer = null

const breakdown = computed(() => {
  const b = data.value?.breakdown || {}
  return {
    educationScore: b.educationScore ?? 0,
    expScore: b.expScore ?? 0,
    skillScore: b.skillScore ?? 0,
    ruleScore: b.ruleScore ?? 0,
    skillTotal: (data.value?.hitSkills?.length || 0) + (data.value?.missSkills?.length || 0)
  }
})

const breakdownItems = computed(() => [
  {
    label: '学历分',
    value: breakdown.value.educationScore,
    max: 40,
    percent: (breakdown.value.educationScore / 40) * 100,
    color: '#2f6bff',
    note: '满足 40 / 低一档 15 / 低两档 0'
  },
  {
    label: '年限分',
    value: breakdown.value.expScore,
    max: 30,
    percent: (breakdown.value.expScore / 30) * 100,
    color: '#0ea5e9',
    note: '达标 30 / 差 1 年 15 / 差 2 年 0'
  },
  {
    label: '技能分',
    value: breakdown.value.skillScore,
    max: 30,
    percent: (breakdown.value.skillScore / 30) * 100,
    color: '#14b8a6',
    note: '命中比例 × 30，四舍五入'
  }
])

const profileKv = computed(() => {
  const p = data.value?.profile
  if (!p) return []
  return [
    { k: '姓名', v: p.name || '—' },
    { k: '学历', v: p.education?.degree || '—' },
    { k: '院校', v: p.education?.school || '—' },
    { k: '专业', v: p.education?.major || '—' },
    { k: '工作年限', v: p.total_years != null ? `${p.total_years} 年` : '—' },
    { k: '电话', v: p.phone || '—' },
    { k: '邮箱', v: p.email || '—' }
  ]
})

onMounted(load)
onBeforeUnmount(() => timer && clearTimeout(timer))

async function load() {
  loading.value = true
  try {
    data.value = await applicationApi.detail(route.params.id)
    if (data.value?.status === 'SCORING' || data.value?.status === 'WAITING') {
      timer = setTimeout(load, 2500)
    }
  } finally {
    loading.value = false
  }
}

async function redo() {
  rescore.value = true
  try {
    await applicationApi.apply(data.value.jobId, data.value.resumeId)
    ElMessage.success('已重新提交打分')
    await load()
  } finally {
    rescore.value = false
  }
}

function goBack() {
  if (data.value?.jobId) router.push(`/board/${data.value.jobId}`)
  else router.push('/board')
}

function statusType(s) {
  return { WAITING: 'info', SCORING: 'warning', SCORED: 'success', FAILED: 'danger' }[s] || 'info'
}

function statusLabel(s) {
  return { WAITING: '待简历解析', SCORING: '打分中', SCORED: '已打分', FAILED: '打分失败' }[s] || s
}

function scoreClass(s) {
  if (!s) return 'muted'
  if (s >= 80) return 'score-high'
  if (s >= 60) return 'score-mid'
  if (s >= 40) return 'score-low'
  return 'score-bad'
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}
</script>

<style scoped>
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(320px, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1280px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}

.score-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.score-hero .big {
  font-size: 46px;
  font-weight: 800;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.formula {
  background: #f7f9ff;
  border: 1px dashed #cfd9f5;
  border-radius: 8px;
  padding: 8px 12px;
  color: #3b5bdb;
}

.break-row {
  display: grid;
  grid-template-columns: 84px 1fr 78px 208px;
  gap: 12px;
  align-items: center;
  padding: 9px 0;
}

.break-label {
  font-size: 13px;
}

.break-value {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.break-note {
  font-size: 12px;
  line-height: 1.4;
}

.reason-box {
  background: #f8fafc;
  border-left: 3px solid var(--accent);
  border-radius: 6px;
  padding: 12px 14px;
  line-height: 1.85;
  font-size: 13.5px;
}

.kv {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed #eef1f6;
}

.kv:last-child {
  border-bottom: none;
}
</style>
