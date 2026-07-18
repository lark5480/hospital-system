<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as pharmacyApi from '@/api/pharmacy'
import type { PrescriptionDetail } from '@/types/pharmacy'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const detail = ref<PrescriptionDetail | null>(null)
const loading = ref(false)

const statusMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
  PENDING: { text: '待发药', type: 'warning' },
  DISPENSING: { text: '发药中', type: '' },
  DISPENSED: { text: '已发药', type: 'success' },
  CANCELLED: { text: '已取消', type: 'info' }
}
const itemStatusMeta: Record<string, string> = {
  PENDING: 'warning',
  DISPENSED: 'success',
  CANCELLED: 'info'
}

async function fetchDetail() {
  loading.value = true
  try {
    detail.value = await pharmacyApi.getPrescription(id)
  } catch {
    ElMessage.error('加载处方详情失败')
  } finally {
    loading.value = false
  }
}

async function handleDispense() {
  try {
    await ElMessageBox.confirm('确认发药？', '发药确认')
    await pharmacyApi.dispensePrescription(id)
    ElMessage.success('发药完成')
    await fetchDetail()
  } catch { /* cancelled */ }
}

async function handleCancel() {
  try {
    await ElMessageBox.confirm('确认取消该处方？', '取消确认')
    await pharmacyApi.cancelPrescription(id)
    ElMessage.success('处方已取消')
    await fetchDetail()
  } catch { /* cancelled */ }
}

onMounted(fetchDetail)
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-button @click="router.push('/pharmacy/prescriptions')">← 返回列表</el-button>
      <h2>处方详情 #{{ id }}</h2>
      <div>
        <el-button v-if="detail?.prescription.status === 'PENDING'" type="primary" @click="handleDispense">发药</el-button>
        <el-button v-if="detail?.prescription.status === 'PENDING'" type="danger" @click="handleCancel">取消</el-button>
      </div>
    </div>

    <template v-if="detail">
      <el-descriptions border :column="3" class="block">
        <el-descriptions-item label="处方号">{{ detail.prescription.id }}</el-descriptions-item>
        <el-descriptions-item label="就诊ID">{{ detail.prescription.visitId }}</el-descriptions-item>
        <el-descriptions-item label="患者">{{ detail.patientName || detail.prescription.patientId }}</el-descriptions-item>
        <el-descriptions-item label="医生">{{ detail.doctorName || detail.prescription.doctorId }}</el-descriptions-item>
        <el-descriptions-item label="药师">{{ (detail.pharmacistName || detail.prescription.pharmacistId) ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusMeta[detail.prescription.status]?.type">{{ statusMeta[detail.prescription.status]?.text }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.prescription.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="发药时间">{{ detail.prescription.dispensedAt ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detail.prescription.remark ?? '-' }}</el-descriptions-item>
      </el-descriptions>

      <h3 class="block">处方明细</h3>
      <el-table :data="detail.items" border stripe empty-text="暂无明细">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="itemName" label="药品名称" min-width="140" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="unitPrice" label="单价" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="itemStatusMeta[row.status] || 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="orderId" label="关联医嘱ID" width="100" />
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; flex: 1; }
.block { margin: 16px 0 8px; }
</style>
