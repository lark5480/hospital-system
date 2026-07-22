<script setup lang="ts">
import { onActivated, onMounted, reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as labApi from '@/api/lab'
import * as chargeApi from '@/api/charge'
import { hasAuthority } from '@/stores/auth'
import type { LabRequisitionDetail } from '@/types/lab'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const detail = ref<LabRequisitionDetail | null>(null)
const loading = ref(false)
const editing = ref(false)
const submitting = ref(false)

const canExecute = computed(() => hasAuthority('order:execute'))
const unpaidVisits = ref<Set<number>>(new Set())

const statusMeta: Record<string, string> = { PENDING: 'warning', EXECUTED: 'success', CANCELLED: 'info' }
const statusText: Record<string, string> = { PENDING: '待检测', EXECUTED: '已完成', CANCELLED: '已取消' }

const resultForm = reactive<Record<number, { resultValue: string; unit: string; refRange: string }>>({})

async function fetchDetail() {
  loading.value = true
  try {
    detail.value = await labApi.getRequisition(id)
    if (detail.value) {
      for (const item of detail.value.items) {
        resultForm[item.id] = {
          resultValue: item.resultValue || '',
          unit: item.unit || '',
          refRange: item.refRange || ''
        }
      }
    }
    // 查询当前就诊单缴费状态(收费前置)
    const status = await chargeApi.batchVisitPaymentStatus([id])
    unpaidVisits.value = status[id] === true ? new Set() : new Set([id])
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function startEdit() {
  editing.value = true
}

async function submitResults() {
  if (!detail.value) return
  submitting.value = true
  try {
    await ElMessageBox.confirm('确认提交检验结果?', '提交确认')
    const items = detail.value.items.map(i => ({
      itemId: i.id,
      resultValue: resultForm[i.id]?.resultValue || '',
      unit: resultForm[i.id]?.unit || '',
      refRange: resultForm[i.id]?.refRange || ''
    }))
    await labApi.submitResults(id, { technicianId: 0, items })
    ElMessage.success('结果已提交')
    editing.value = false
    await fetchDetail()
  } catch { /* cancelled */ }
  finally { submitting.value = false }
}

async function handleCancel() {
  try {
    await ElMessageBox.confirm('确认取消该申请?', '取消确认')
    await labApi.cancelRequisition(id)
    ElMessage.success('申请已取消')
    await fetchDetail()
  } catch { /* cancelled */ }
}

onMounted(fetchDetail)
onActivated(fetchDetail)
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-button @click="router.push('/lab/requisitions')">返回列表</el-button>
      <h2>检验申请 #{{ id }}</h2>
      <div>
        <!-- 收费前置:未缴费时禁用录入/提交 -->
        <el-tooltip v-if="detail && unpaidVisits.has(detail.requisition.visitId)" content="该就诊未缴费,请先结算" placement="top">
          <span>
            <el-button type="primary" disabled>未缴费</el-button>
          </span>
        </el-tooltip>
        <template v-else>
          <el-button v-if="detail?.requisition.status === 'PENDING' && !editing && canExecute" type="primary" @click="startEdit">录入结果</el-button>
          <el-button v-if="editing" type="success" :loading="submitting" @click="submitResults">提交结果</el-button>
        </template>
        <el-button v-if="editing" @click="editing = false">取消</el-button>
        <el-button v-if="detail?.requisition.status === 'PENDING' && canExecute" type="danger" @click="handleCancel">取消申请</el-button>
      </div>
    </div>

    <template v-if="detail">
      <el-descriptions border :column="3" class="block">
        <el-descriptions-item label="申请号">{{ detail.requisition.id }}</el-descriptions-item>
        <el-descriptions-item label="就诊ID">{{ detail.requisition.visitId }}</el-descriptions-item>
        <el-descriptions-item label="患者ID">{{ detail.requisition.patientId }}</el-descriptions-item>
        <el-descriptions-item label="申请医生">{{ detail.requisition.doctorId }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusMeta[detail.requisition.status] || ''">{{ statusText[detail.requisition.status] }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.requisition.createdAt }}</el-descriptions-item>
      </el-descriptions>

      <h3 class="block">检测项目</h3>
      <el-table :data="detail.items" border stripe empty-text="暂无项目">
        <el-table-column prop="itemName" label="项目名称" width="140" />
        <el-table-column label="结果值" width="120">
          <template #default="{ row }">
            <el-input v-if="editing" v-model="resultForm[row.id].resultValue" size="small" />
            <span v-else>{{ row.resultValue || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="80">
          <template #default="{ row }">
            <el-input v-if="editing" v-model="resultForm[row.id].unit" size="small" />
            <span v-else>{{ row.unit || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="参考范围" width="120">
          <template #default="{ row }">
            <el-input v-if="editing" v-model="resultForm[row.id].refRange" size="small" />
            <span v-else>{{ row.refRange || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标记" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.abnormalFlag === 'HIGH'" type="danger" size="small">偏高 ↑</el-tag>
            <el-tag v-else-if="row.abnormalFlag === 'LOW'" type="warning" size="small">偏低 ↓</el-tag>
            <el-tag v-else-if="row.abnormalFlag === 'NORMAL'" type="success" size="small">正常</el-tag>
            <el-tag v-else type="info" size="small">待检测</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'COMPLETED' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; flex: 1; }
.block { margin: 16px 0 8px; }
</style>