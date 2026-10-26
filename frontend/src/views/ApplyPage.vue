<template>
  <div class="apply-page">
    <!-- 顶部 -->
    <header class="apply-header">
      <div class="apply-wrap header-inner">
        <div class="brand">
          <div class="brand-mark">云</div>
          <div>
            <b>云图数据科技</b>
            <span>社会招聘</span>
          </div>
        </div>
        <div class="header-hint">简历将用于本岗位评估，不会用于其他用途</div>
      </div>
    </header>

    <div class="apply-wrap apply-body" v-loading="loading">
      <div v-if="notFound" class="card empty-state">
        <el-icon :size="40" color="#c0c7d4"><WarningFilled/></el-icon>
        <h2>岗位不存在或已下线</h2>
        <p class="muted">请通过 HR 提供的投递链接重新进入</p>
      </div>

      <template v-else-if="job">
        <!-- 左：岗位信息 -->
        <section class="job-col">
          <div class="card">
            <div class="job-head">
              <h1>{{ job.title }}</h1>
              <el-tag :type="job.open ? 'success' : 'info'" effect="light" round size="small">
                {{ job.open ? '招聘中' : '已关闭' }}
              </el-tag>
            </div>

            <div class="job-meta">
              <span v-if="job.department"><el-icon><OfficeBuilding/></el-icon>{{ job.department }}</span>
              <span><el-icon><School/></el-icon>{{ job.educationReq }}及以上</span>
              <span><el-icon><Clock/></el-icon>{{ job.expYearsReq ? job.expYearsReq + ' 年以上' : '经验不限' }}</span>
            </div>

            <div class="skill-block" v-if="job.mustSkills?.length">
              <div class="label">必备技能</div>
              <div class="chip-row">
                <span v-for="s in job.mustSkills" :key="s" class="skill-chip">{{ s }}</span>
              </div>
            </div>

            <div class="skill-block" v-if="job.plusSkills?.length">
              <div class="label">加分项</div>
              <div class="chip-row">
                <span v-for="s in job.plusSkills" :key="s" class="skill-chip plus">{{ s }}</span>
              </div>
            </div>

            <div class="skill-block" v-if="job.jdText">
              <div class="label">岗位描述</div>
              <div class="jd-text">{{ job.jdText }}</div>
            </div>
          </div>
        </section>

        <!-- 右：投递表单 -->
        <aside class="form-col">
          <div class="card form-card">
            <template v-if="submitted">
              <div class="success">
                <el-icon :size="46" color="#16a34a"><CircleCheckFilled/></el-icon>
                <h2>{{ resultMessage }}</h2>
                <p class="muted">
                  {{ resultDuplicated ? '同一份简历投一次就够了，HR 已经收到。' : '我们已经收到你的简历，HR 会尽快查看并与你联系。' }}
                </p>
                <el-button v-if="resultDuplicated" plain @click="reset">换一份简历重新投递</el-button>
              </div>
            </template>

            <template v-else>
              <h3 class="form-title">投递这个岗位</h3>
              <p class="muted form-sub">带 <em>*</em> 为必填，简历里已有的信息会自动识别</p>

              <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large">
                <el-form-item label="姓名" prop="name">
                  <el-input v-model="form.name" placeholder="你的姓名" maxlength="50"/>
                </el-form-item>

                <el-form-item label="手机号" prop="phone">
                  <el-input v-model="form.phone" placeholder="用于 HR 联系你" maxlength="20"/>
                </el-form-item>

                <el-form-item label="邮箱" prop="email">
                  <el-input v-model="form.email" placeholder="选填，建议填写" maxlength="100"/>
                </el-form-item>

                <el-form-item label="简历（PDF）" prop="file">
                  <el-upload
                    drag
                    :auto-upload="false"
                    :show-file-list="false"
                    accept="application/pdf"
                    :on-change="onFileChange"
                    class="uploader"
                  >
                    <div v-if="!form.file" class="upload-empty">
                      <el-icon :size="30" color="#a8b2c2"><UploadFilled/></el-icon>
                      <div>拖入 PDF 或<em>点击选择</em></div>
                      <div class="muted tiny">仅支持文本型 PDF，不超过 10MB</div>
                    </div>
                    <div v-else class="upload-file">
                      <el-icon :size="22" color="#16a34a"><Document/></el-icon>
                      <div>
                        <div class="file-name">{{ form.file.name }}</div>
                        <div class="muted tiny">{{ (form.file.size / 1024).toFixed(0) }} KB · 点击可更换</div>
                      </div>
                    </div>
                  </el-upload>
                </el-form-item>
              </el-form>

              <el-alert
                v-if="!job.open"
                type="warning"
                :closable="false"
                show-icon
                title="该岗位已关闭投递"
                style="margin-bottom: 14px"
              />

              <el-button
                type="primary"
                size="large"
                style="width: 100%"
                :loading="submitting"
                :disabled="!job.open"
                @click="submit"
              >
                提交简历
              </el-button>

              <p class="agree muted tiny">
                提交即表示同意我们为本次招聘评估处理你的简历信息。
              </p>
            </template>
          </div>
        </aside>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { publicApi } from '../api'

const route = useRoute()
const jobId = Number(route.params.jobId)

const job = ref(null)
const loading = ref(false)
const notFound = ref(false)
const submitting = ref(false)
const submitted = ref(false)
const resultMessage = ref('')
const resultDuplicated = ref(false)

const formRef = ref()
const form = reactive({ name: '', phone: '', email: '', file: null })

const rules = {
  name: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  phone: [
    {
      validator: (_, v, cb) => {
        if (!v && !form.email) return cb(new Error('手机号和邮箱至少填一个'))
        if (v && !/^[0-9+\-\s()]{6,20}$/.test(v)) return cb(new Error('手机号格式不正确'))
        cb()
      },
      trigger: 'blur'
    }
  ],
  email: [
    {
      validator: (_, v, cb) => {
        if (!v && !form.phone) return cb(new Error('手机号和邮箱至少填一个'))
        if (v && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v)) return cb(new Error('邮箱格式不正确'))
        cb()
      },
      trigger: 'blur'
    }
  ],
  file: [
    {
      validator: (_, __, cb) => (form.file ? cb() : cb(new Error('请上传 PDF 简历'))),
      trigger: 'change'
    }
  ]
}

onMounted(async () => {
  if (!Number.isFinite(jobId)) {
    notFound.value = true
    return
  }
  loading.value = true
  try {
    job.value = await publicApi.job(jobId)
  } catch (e) {
    notFound.value = true
  } finally {
    loading.value = false
  }
})

function onFileChange(uploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  if (!raw.name.toLowerCase().endsWith('.pdf') && raw.type !== 'application/pdf') {
    ElMessage.warning('只接受 PDF 文件')
    return
  }
  if (raw.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 10MB')
    return
  }
  form.file = raw
  formRef.value?.validateField('file').catch(() => {})
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  submitting.value = true
  try {
    const r = await publicApi.apply({
      jobId,
      name: form.name,
      phone: form.phone,
      email: form.email,
      file: form.file
    })
    submitted.value = true
    resultMessage.value = r.message || '投递成功'
    resultDuplicated.value = !!r.duplicated
  } finally {
    submitting.value = false
  }
}

function reset() {
  submitted.value = false
  form.file = null
  formRef.value?.clearValidate()
}
</script>

<style scoped>
.apply-page {
  min-height: 100%;
  background: #f2f4f8;
  padding-bottom: 60px;
}

.apply-wrap {
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 24px;
}

.apply-header {
  background: #fff;
  border-bottom: 1px solid var(--border);
  margin-bottom: 26px;
}

.header-inner {
  height: 66px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #2f6bff, #6f9bff);
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand b {
  display: block;
  font-size: 15px;
  line-height: 1.3;
}

.brand span {
  font-size: 11.5px;
  color: var(--text-sub);
  letter-spacing: .5px;
}

.header-hint {
  font-size: 12.5px;
  color: var(--text-sub);
}

.apply-body {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(330px, 400px);
  gap: 20px;
  align-items: start;
}

@media (max-width: 980px) {
  .apply-body {
    grid-template-columns: 1fr;
  }
}

.card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 26px 28px;
}

.job-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.job-head h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: .3px;
}

.job-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin: 14px 0 6px;
  color: var(--text-sub);
  font-size: 13.5px;
}

.job-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.skill-block {
  margin-top: 22px;
}

.skill-block .label {
  font-size: 13px;
  color: var(--text-sub);
  margin-bottom: 9px;
}

.jd-text {
  white-space: pre-wrap;
  line-height: 1.95;
  font-size: 13.5px;
  color: #374151;
  background: #fafbfd;
  border: 1px solid #eef1f6;
  border-radius: 10px;
  padding: 16px 18px;
}

.form-col {
  position: sticky;
  top: 20px;
}

.form-card {
  padding: 24px 24px 20px;
}

.form-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

.form-sub {
  margin: 6px 0 18px;
  font-size: 12.5px;
}

.form-sub em {
  color: #dc2626;
  font-style: normal;
}

.uploader :deep(.el-upload-dragger) {
  padding: 20px 12px;
  border-radius: 10px;
}

.upload-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.upload-empty em {
  color: var(--accent);
  font-style: normal;
}

.upload-file {
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
}

.file-name {
  font-size: 13px;
  font-weight: 600;
  max-width: 250px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tiny {
  font-size: 11.5px;
  line-height: 1.6;
}

.agree {
  margin: 12px 0 0;
  text-align: center;
}

.success {
  text-align: center;
  padding: 28px 6px 18px;
}

.success h2 {
  margin: 14px 0 8px;
  font-size: 18px;
}

.success p {
  margin: 0 0 18px;
  font-size: 13px;
  line-height: 1.8;
}

.empty-state {
  grid-column: 1 / -1;
  text-align: center;
  padding: 60px 20px;
}

.empty-state h2 {
  margin: 12px 0 6px;
  font-size: 18px;
}
</style>
