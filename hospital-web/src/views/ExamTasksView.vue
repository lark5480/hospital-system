<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as examApi from '@/api/exam'
import * as chargeApi from '@/api/charge'
import type { ExamTask } from '@/api/exam'

/** 从任务列表提取去重 visitId。 */
function distinctVisitIds(tasks: ExamTask[]): number[] {
  return [...new Set(tasks.map(t => t.visitId))]
}

const list = ref<ExamTask[]>([])
const loading = ref(false)
const executing = ref<number | null>(null)
// visitId → 是否有未缴费:true=拦截
const unpaidVisits = ref<Set<number>>(new Set())

async function fetchList() {
  loading.value = true
  try {
    list.value = await examApi.listPendingExams()
    // 批量查去重 visitId 的缴费状态:true=已缴清
    const status = await chargeApi.batchVisitPaymentStatus(distinctVisitIds(list.value))
    unpaidVisits.value = new Set(
      list.value.filter(t => status[t.visitId] !== true).map(t => t.visitId)
    )
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

async function handleExecute(task: ExamTask) {
  executing.value = task.orderId
  try {
    await examApi.executeExam(task.visitId, task.orderId)
    ElMessage.success(`检查已执行: ${task.itemName}`)
    await fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '执行失败')
  } finally {
    executing.value = null
  }
}

onMounted(fetchList)
onActivated(fetchList)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>检查执行</h2>
      <el-button type="primary" @click="fetchList" :loading="loading">刷新</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe empty-text="暂无待执行的检查">
      <el-table-column prop="orderId" label="医嘱ID" width="80" />
      <el-table-column prop="visitId" label="就诊ID" width="80" />
      <el-table-column label="患者" width="100">
        <template #default="{ row }">{{ row.patientName || '-' }}</template>
      </el-table-column>
      <el-table-column label="开单医生" width="100">
        <template #default="{ row }">{{ row.doctorName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="itemName" label="检查项目" min-width="160" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-tooltip v-if="unpaidVisits.has(row.visitId)" content="该就诊未缴费,请先结算" placement="top">
            <span>
              <el-button type="primary" size="small" disabled>未缴费</el-button>
            </span>
          </el-tooltip>
          <el-button v-else type="primary" size="small" :loading="executing === row.orderId"
            @click="handleExecute(row)">执行检查</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; }
</style>
