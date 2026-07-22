<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as examApi from '@/api/exam'
import * as chargeApi from '@/api/charge'
import type { ExamTask } from '@/api/exam'

const activeTab = ref('pending')
const pendingList = ref<ExamTask[]>([])
const doneList = ref<ExamTask[]>([])
const loading = ref(false)
const executing = ref<number | null>(null)
const unpaidVisits = ref<Set<number>>(new Set())

// Finding dialog state
const findingDialog = ref(false)
const findingText = ref('')
const pendingOrderId = ref<number | null>(null)
const pendingVisitId = ref<number | null>(null)

function distinctVisitIds(tasks: ExamTask[]): number[] {
  return [...new Set(tasks.map(t => t.visitId))]
}

async function fetchPending() {
  pendingList.value = await examApi.listExams('CREATED')
  const status = await chargeApi.batchVisitPaymentStatus(distinctVisitIds(pendingList.value))
  unpaidVisits.value = new Set(
    pendingList.value.filter(t => status[t.visitId] !== true).map(t => t.visitId)
  )
}

async function fetchDone() {
  doneList.value = await examApi.listExams('EXECUTED')
}

async function fetchAll() {
  loading.value = true
  try {
    if (activeTab.value === 'pending') await fetchPending()
    else await fetchDone()
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function handleExecute(task: ExamTask) {
  pendingOrderId.value = task.orderId
  pendingVisitId.value = task.visitId
  findingText.value = ''
  findingDialog.value = true
}

async function submitFinding() {
  if (!pendingOrderId.value || !pendingVisitId.value) return
  executing.value = pendingOrderId.value
  try {
    await examApi.executeExam(pendingVisitId.value, pendingOrderId.value, findingText.value || undefined)
    ElMessage.success('检查已执行')
    findingDialog.value = false
    await fetchAll()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '执行失败')
  } finally {
    executing.value = null
  }
}

onMounted(fetchAll)
onActivated(fetchAll)

function onTabChange() { fetchAll() }
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>检查执行</h2>
      <el-button type="primary" @click="fetchAll" :loading="loading">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="待检查" name="pending">
        <el-table :data="pendingList" v-loading="loading" border stripe empty-text="暂无待执行的检查">
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
      </el-tab-pane>

      <el-tab-pane label="已完成" name="done">
        <el-table :data="doneList" v-loading="loading" border stripe empty-text="暂无已完成的检查">
          <el-table-column prop="orderId" label="医嘱ID" width="80" />
          <el-table-column prop="visitId" label="就诊ID" width="80" />
          <el-table-column label="患者" width="100">
            <template #default="{ row }">{{ row.patientName || '-' }}</template>
          </el-table-column>
          <el-table-column label="开单医生" width="100">
            <template #default="{ row }">{{ row.doctorName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="itemName" label="检查项目" min-width="140" />
          <el-table-column label="检查所见" min-width="200">
            <template #default="{ row }">
              <span v-if="row.finding">{{ row.finding }}</span>
              <el-tag v-else type="info" size="small">未填写</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 执行检查:录入检查所见 -->
    <el-dialog v-model="findingDialog" title="执行检查 — 录入检查所见" width="520px">
      <el-form label-width="90px">
        <el-form-item label="检查所见">
          <el-input v-model="findingText" type="textarea" :rows="6"
            placeholder="请描述检查所见，如：肝区未见明显异常回声，胆囊壁光滑，胰腺显示清楚..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="findingDialog = false">取消</el-button>
        <el-button type="primary" :loading="executing !== null" @click="submitFinding">确认执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; }
</style>
