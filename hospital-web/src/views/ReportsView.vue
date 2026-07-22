<script setup lang="ts">
import { onActivated, onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as reportApi from '@/api/report'
import type { Report, ReportDetail } from '@/types/report'

const list = ref<Report[]>([])
const loading = ref(false)
const filterType = ref('')
const detailDialog = ref(false)
const selectedReport = ref<ReportDetail | null>(null)
const publishing = ref(false)

const typeMeta: Record<string, string> = { LAB: '检验报告', EXAM: '检查报告', CLINICAL: '门诊病历' }
const typeMetaReverse: Record<string, string> = { '全部': '', '检验报告': 'LAB', '检查报告': 'EXAM', '门诊病历': 'CLINICAL' }
const typeOptions = ['全部', '检验报告', '检查报告', '门诊病历']

const statusMeta: Record<string, { text: string; type: 'info' | 'success' }> = {
  DRAFT: { text: '草稿', type: 'info' },
  PUBLISHED: { text: '已发布', type: 'success' }
}

const stats = computed(() => ({
  total: list.value.length,
  draft: list.value.filter(r => r.status === 'DRAFT').length,
  published: list.value.filter(r => r.status === 'PUBLISHED').length
}))

async function fetchList() {
  loading.value = true
  try {
    list.value = filterType.value
      ? await reportApi.listReportsByType(filterType.value)
      : await reportApi.listAllReports()
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function viewDetail(report: Report) {
  selectedReport.value = report
  detailDialog.value = true
}

async function handlePublish(report: Report) {
  try {
    await ElMessageBox.confirm(`确认发布「${report.title}」？`, '发布确认')
    publishing.value = true
    await reportApi.publishReport(report.id)
    ElMessage.success('报告已发布')
    await fetchList()
  } catch {
    // cancelled
  } finally {
    publishing.value = false
  }
}

onMounted(fetchList)
onActivated(fetchList)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>报告管理</h2>
      <div class="toolbar-right">
        <span class="stats">
          <span>共 {{ stats.total }} 份</span>
          <el-tag size="small" type="info" style="margin-left:8px">{{ stats.draft }} 份草稿</el-tag>
          <el-tag size="small" type="success" style="margin-left:4px">{{ stats.published }} 份已发布</el-tag>
        </span>
        <el-select v-model="filterType" placeholder="报告类型" clearable style="width:160px" @change="fetchList">
          <el-option v-for="opt in typeOptions" :key="opt" :label="opt" :value="typeMetaReverse[opt]" />
        </el-select>
        <el-button type="primary" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border stripe empty-text="暂无报告">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ typeMeta[row.type] || row.type }}</template>
      </el-table-column>
      <el-table-column label="患者" width="100">
        <template #default="{ row }">{{ row.patientName || row.patientId }}</template>
      </el-table-column>
      <el-table-column prop="visitId" label="就诊ID" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status]?.type || 'info'" size="small">
            {{ statusMeta[row.status]?.text || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column prop="publishedAt" label="发布时间" width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="viewDetail(row)">查看</el-button>
          <el-button v-if="row.status === 'DRAFT'" size="small" type="success"
            :loading="publishing" @click="handlePublish(row)">发布</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="detailDialog" title="报告详情" width="640px">
      <template v-if="selectedReport">
        <el-descriptions border :column="2">
          <el-descriptions-item label="标题">{{ selectedReport.title }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ typeMeta[selectedReport.type] || selectedReport.type }}</el-descriptions-item>
          <el-descriptions-item label="患者">{{ selectedReport.patientName || selectedReport.patientId }}</el-descriptions-item>
          <el-descriptions-item label="性别/电话">{{ selectedReport.patientGender || '-' }} / {{ selectedReport.patientPhone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="就诊ID">{{ selectedReport.visitId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMeta[selectedReport.status]?.type || 'info'" size="small">
              {{ statusMeta[selectedReport.status]?.text || selectedReport.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="医生">{{ selectedReport.doctorName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="科室">{{ selectedReport.deptName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="主诉" :span="2">{{ selectedReport.visitChiefComplaint || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ selectedReport.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="发布时间" :span="2">{{ selectedReport.publishedAt || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="report-content">
          <h4>报告内容</h4>
          <div class="content-body">{{ selectedReport.content || '(无内容)' }}</div>
        </div>
      </template>
      <template #footer>
        <el-button @click="detailDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--sp-4); flex-wrap: wrap; gap: var(--sp-2); }
.toolbar h2 { margin: 0; font-size: var(--fs-xl); }
.toolbar-right { display: flex; align-items: center; gap: var(--sp-2); }
.stats { font-size: var(--fs-sm); color: var(--text-regular); }
.report-content { margin-top: var(--sp-4); }
.report-content h4 { margin: 0 0 var(--sp-2); font-size: var(--fs-base); }
.content-body {
  background: var(--slate-100); padding: var(--sp-3); border-radius: var(--radius-sm);
  white-space: pre-wrap; font-size: var(--fs-sm); line-height: 1.6;
  max-height: 300px; overflow-y: auto;
}
</style>
