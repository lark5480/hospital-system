<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { usePatientStore } from '@/stores/patient'
import type { ReportRecord } from '@/types/patient'
import { downloadReport } from '@/api/patient'
import { saveBlob } from '@/api/file'

const patientStore = usePatientStore()
const reportDialogVisible = ref(false)
const selectedReport = ref<ReportRecord | null>(null)
const downloading = ref<number | null>(null)

const typeMeta: Record<string, string> = {
  LAB: '检验报告',
  EXAM: '检查报告',
  CLINICAL: '门诊病历'
}

onMounted(async () => {
  await patientStore.fetchMe()
  await patientStore.fetchMyReports()
})

function viewReport(report: ReportRecord) {
  selectedReport.value = report
  reportDialogVisible.value = true
}

async function downloadPdf(report: ReportRecord) {
  downloading.value = report.id
  try {
    const blob = await downloadReport(report.id)
    saveBlob(blob, `report-${report.id}.pdf`)
    ElMessage.success('已开始下载 PDF')
  } catch {
    ElMessage.error('下载失败,报告文件可能仍在生成中')
  } finally {
    downloading.value = null
  }
}
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>我的报告</h2>
      <el-button @click="patientStore.fetchMyReports()" :loading="patientStore.loading">刷新</el-button>
    </div>

    <el-table
      :data="patientStore.reports"
      v-loading="patientStore.loading"
      border
      stripe
      empty-text="暂无报告"
    >
      <el-table-column prop="title" label="报告标题" min-width="180" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ typeMeta[row.type] ?? row.type }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">
            {{ row.status === 'PUBLISHED' ? '已发布' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="publishedAt" label="报告日期" width="180" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button type="primary" link @click="viewReport(row)">查看</el-button>
          <el-button type="success" link :loading="downloading === row.id" @click="downloadPdf(row)">
            下载PDF
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 报告详情弹窗 -->
    <el-dialog v-model="reportDialogVisible" :title="selectedReport?.title ?? '报告详情'" width="700px">
      <template v-if="selectedReport">
        <el-descriptions :column="1" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="类型">{{ typeMeta[selectedReport.type] ?? selectedReport.type }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ selectedReport.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-descriptions-item>
          <el-descriptions-item label="报告日期">{{ selectedReport.publishedAt ?? selectedReport.createdAt }}</el-descriptions-item>
        </el-descriptions>
        <div class="report-content">{{ selectedReport.content }}</div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar h2 {
  margin: 0;
  font-size: 18px;
}
.report-content {
  white-space: pre-wrap;
  line-height: 1.8;
  background: #fafafa;
  padding: 16px;
  border-radius: 6px;
  font-size: 14px;
}
</style>
