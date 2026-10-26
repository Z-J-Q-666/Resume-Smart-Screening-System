<template>
  <div class="page-head">
    <div>
      <h1>岗位管理</h1>
      <p>建岗位 → 复制投递链接发给求职者 → 回到「候选人看板」看分数排行</p>
    </div>
    <el-button type="primary" :icon="Plus" @click="openCreate">新建岗位</el-button>
  </div>

  <div class="page-body">
    <div class="panel">
      <el-table :data="jobs" v-loading="store.loading" stripe style="width: 100%">
        <el-table-column label="岗位" min-width="180">
          <template #default="{ row }">
            <div style="font-weight: 600">{{ row.title }}</div>
            <div class="muted" style="font-size: 12px; margin-top: 2px">
              {{ row.department || '未填部门' }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="要求" width="100">
          <template #default="{ row }">
            <span>{{ row.educationReq }}</span>
            <span class="muted"> · </span>
            <span>{{ row.expYearsReq }} 年</span>
          </template>
        </el-table-column>

        <el-table-column label="必备技能" min-width="150">
          <template #default="{ row }">
            <div class="chip-row">
              <span v-for="s in (row.mustSkills || []).slice(0, 2)" :key="s" class="skill-chip">{{ s }}</span>
              <span v-if="(row.mustSkills || []).length > 2" class="muted" style="font-size: 12px">
                +{{ row.mustSkills.length - 2 }}
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="投递 / 均分" width="96" align="center">
          <template #default="{ row }">
            <b>{{ row.applicationCount ?? 0 }}</b>
            <span class="muted"> / </span>
            <span>{{ row.avgScore != null ? row.avgScore : '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="投递页" width="96" align="center">
          <template #default="{ row }">
            <el-tooltip content="复制给求职者的投递链接" placement="top">
              <el-button link type="primary" @click.stop="copyApplyLink(row)">复制链接</el-button>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="72" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light" round size="small">
              {{ row.status === 1 ? '开放' : '已关闭' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="168" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goBoard(row)">看板</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '关闭' : '开放' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!store.loading && !jobs.length" class="empty">
        还没有岗位，先建一个吧
      </div>
    </div>
  </div>

  <el-dialog
    v-model="dialogVisible"
    :title="editing ? '编辑岗位' : '新建岗位'"
    width="720px"
    top="6vh"
    destroy-on-close
  >
    <el-form :model="form" label-width="96px" :rules="rules" ref="formRef">
      <el-form-item label="岗位名称" prop="title">
        <el-input v-model="form.title" maxlength="100" placeholder="如：Java 后端开发工程师"/>
      </el-form-item>

      <el-form-item label="部门">
        <el-input v-model="form.department" maxlength="100" placeholder="如：交易中台"/>
      </el-form-item>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="学历要求">
            <el-select v-model="form.educationReq" style="width: 100%">
              <el-option v-for="d in degrees" :key="d" :label="d" :value="d"/>
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="年限要求">
            <el-input-number v-model="form.expYearsReq" :min="0" :max="30" style="width: 100%"/>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="必备技能" prop="mustSkills">
        <el-select
          v-model="form.mustSkills"
          multiple
          filterable
          allow-create
          default-first-option
          :reserve-keyword="false"
          placeholder="输入后回车添加，如 Java / Spring Boot / MySQL"
          style="width: 100%"
        >
          <el-option v-for="s in skillPresets" :key="s" :label="s" :value="s"/>
        </el-select>
        <div class="muted" style="font-size: 12px; line-height: 1.7; margin-top: 4px">
          1–20 项。匹配时按大小写不敏感比较，SpringBoot 与 Spring Boot 视为同一项
        </div>
      </el-form-item>

      <el-form-item label="加分技能">
        <el-select
          v-model="form.plusSkills"
          multiple
          filterable
          allow-create
          default-first-option
          :reserve-keyword="false"
          placeholder="不参与规则分，供语义判断参考"
          style="width: 100%"
        >
          <el-option v-for="s in skillPresets" :key="s" :label="s" :value="s"/>
        </el-select>
      </el-form-item>

      <el-form-item label="JD 原文">
        <el-input
          v-model="form.jdText"
          type="textarea"
          :rows="8"
          placeholder="粘贴岗位职责与任职要求，语义分会读这段文本"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { jobApi } from '../api'
import { useJobStore } from '../stores/job'

const router = useRouter()
const store = useJobStore()

const jobs = computed(() => store.jobs)
const degrees = ['不限', '大专', '本科', '硕士', '博士']
const skillPresets = [
  'Java', 'Spring Boot', 'Spring Cloud', 'MyBatis', 'MySQL', 'Redis', 'RocketMQ',
  'Kafka', 'Docker', 'Linux', 'Vue', 'JavaScript', 'TypeScript', 'Python', 'Go', 'Elasticsearch'
]

const dialogVisible = ref(false)
const editing = ref(null)
const saving = ref(false)
const formRef = ref()

const form = reactive({
  title: '',
  department: '',
  educationReq: '本科',
  expYearsReq: 3,
  mustSkills: [],
  plusSkills: [],
  jdText: ''
})

const rules = {
  title: [{ required: true, message: '请填写岗位名称', trigger: 'blur' }],
  mustSkills: [
    {
      validator: (_, v, cb) => {
        if (!v || !v.length) cb(new Error('必备技能至少 1 项'))
        else if (v.length > 20) cb(new Error('必备技能最多 20 项'))
        else cb()
      },
      trigger: 'change'
    }
  ]
}

onMounted(() => store.fetchList(true))

function reset() {
  Object.assign(form, {
    title: '',
    department: '',
    educationReq: '本科',
    expYearsReq: 3,
    mustSkills: [],
    plusSkills: [],
    jdText: ''
  })
}

function openCreate() {
  editing.value = null
  reset()
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    title: row.title,
    department: row.department || '',
    educationReq: row.educationReq,
    expYearsReq: row.expYearsReq,
    mustSkills: [...(row.mustSkills || [])],
    plusSkills: [...(row.plusSkills || [])],
    jdText: row.jdText || ''
  })
  dialogVisible.value = true
}

async function submit() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await jobApi.update(editing.value.id, form)
      ElMessage.success('已保存')
    } else {
      await jobApi.create(form)
      ElMessage.success('岗位已创建')
    }
    dialogVisible.value = false
    await store.fetchList(true)
  } finally {
    saving.value = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  await ElMessageBox.confirm(
    next === 0 ? '关闭后该岗位不再接受新投递，确定吗？' : '重新开放该岗位？',
    '提示',
    { type: 'warning' }
  )
  await jobApi.updateStatus(row.id, next)
  ElMessage.success('已更新')
  await store.fetchList(true)
}

function goBoard(row) {
  store.setCurrent(row.id)
  router.push(`/board/${row.id}`)
}

/** 给求职者的投递页地址，复制到招聘公告 / 公众号 / 邮件里 */
async function copyApplyLink(row) {
  const url = `${window.location.origin}/apply/${row.id}`
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success(`已复制：${url}`)
  } catch (e) {
    // 非 https / 浏览器不支持时退化为手选
    ElMessageBox.alert(url, '投递链接（手动复制）', { confirmButtonText: '知道了' })
  }
}
</script>
