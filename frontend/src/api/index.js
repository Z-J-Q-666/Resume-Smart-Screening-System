import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000,
  headers: { 'X-User-Id': 'hr' }
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body.data
  },
  (error) => {
    const body = error.response?.data
    const msg = body?.message || error.message || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export const jobApi = {
  list: (params) => http.get('/jobs', { params }),
  detail: (id) => http.get(`/jobs/${id}`),
  create: (data) => http.post('/jobs', data),
  update: (id, data) => http.put(`/jobs/${id}`, data),
  updateStatus: (id, status) => http.put(`/jobs/${id}/status`, { status }),
  board: (id, params) => http.get(`/jobs/${id}/applications`, { params }),
  top: (id, n = 20) => http.get(`/jobs/${id}/top`, { params: { n } })
}

export const resumeApi = {
  list: (params) => http.get('/resumes', { params }),
  detail: (id) => http.get(`/resumes/${id}`),
  status: (id) => http.get(`/resumes/${id}/status`),
  retry: (id) => http.post(`/resumes/${id}/retry`),
  upload: (file, onProgress) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/resumes', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: onProgress
    })
  },
  batchUpload: (files) => {
    const form = new FormData()
    files.forEach((f) => form.append('files', f))
    return http.post('/resumes/batch', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

export const applicationApi = {
  apply: (jobId, resumeId) => http.post('/applications', { jobId, resumeId }),
  detail: (id) => http.get(`/applications/${id}`)
}

/** 求职者侧（官网投递），不带 X-User-Id，走公开接口 */
export const publicApi = {
  job: (jobId) => http.get(`/public/jobs/${jobId}`),
  apply: ({ jobId, name, phone, email, file }) => {
    const form = new FormData()
    form.append('jobId', jobId)
    if (name) form.append('name', name)
    if (phone) form.append('phone', phone)
    if (email) form.append('email', email)
    form.append('file', file)
    return http.post('/public/applications', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

export default http
