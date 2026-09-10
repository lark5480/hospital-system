<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  uploadFile,
  listFiles,
  downloadFile,
  saveBlob,
  type UploadResult,
  type FileMeta
} from '@/api/file'

const uploading = ref(false)
const uploaded = ref<UploadResult[]>([])
const reportFiles = ref<FileMeta[]>([])
const loadingFiles = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

async function onPick(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const res = await uploadFile(file)
    uploaded.value.unshift(res)
    ElMessage.success(`上传成功:${res.objectName}`)
  } catch {
    ElMessage.error('上传失败,请确认文件服务已启动')
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function loadReportFiles() {
  loadingFiles.value = true
  try {
    reportFiles.value = await listFiles({ bizType: 'REPORT' })
  } catch {
    ElMessage.error('读取文件列表失败,请确认文件服务已启动')
  } finally {
    loadingFiles.value = false
  }
}

async function downloadMeta(f: FileMeta) {
  // R-62: 下载必须带 patientId —— 它是服务端归属校验的依据。
  // 列表里 patientId 为空的对象(历史数据/无归属上传)无法通过校验,直接提示而不是发一个必然 403 的请求。
  if (f.patientId == null) {
    ElMessage.warning('该文件没有患者归属,无法下载')
    return
  }
  try {
    const blob = await downloadFile(f.objectName, f.patientId)
    saveBlob(blob, f.objectName)
  } catch {
    ElMessage.error('下载失败')
  }
}

function fmtSize(n: number) {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

onMounted(loadReportFiles)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>文件管理</h2>
      <input ref="fileInput" type="file" style="display: none" @change="onPick" />
      <el-button type="primary" :icon="UploadFilled" :loading="uploading" @click="fileInput?.click()">
        上传附件
      </el-button>
    </div>
    <el-alert
      class="hint"
      type="info"
      :closable="false"
      title="文件服务为独立抽出服务,经 core 的 /api/core/files 代理调用 MinIO,演示微服务独立伸缩与对象存储接入。体检报告 PDF 自动生成并归档,可在下方查询与下载。"
    />

    <el-table :data="uploaded" border stripe empty-text="暂无上传记录" style="margin-top: 12px">
      <el-table-column type="index" label="#" width="60" />
      <el-table-column prop="objectName" label="对象名" min-width="280" />
      <el-table-column prop="bucket" label="存储桶" width="140" />
    </el-table>

    <el-divider />

    <div class="toolbar">
      <h3>报告文件(归档 / 可查询)</h3>
      <el-button @click="loadReportFiles" :loading="loadingFiles">刷新列表</el-button>
    </div>
    <el-table
      :data="reportFiles"
      v-loading="loadingFiles"
      border
      stripe
      empty-text="暂无报告文件"
      style="margin-top: 8px"
    >
      <el-table-column prop="objectName" label="对象名" min-width="240" />
      <el-table-column label="患者ID" width="110">
        <template #default="{ row }">{{ row.patientId ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">{{ fmtSize(row.size) }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="生成时间" min-width="200" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button type="success" link @click="downloadMeta(row)">下载</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--sp-4); }
.toolbar h2 { margin: 0; font-size: var(--fs-xl); }
.toolbar h3 { margin: 0; font-size: var(--fs-md); color: var(--brand); }
.hint { margin-bottom: var(--sp-1); }
</style>
