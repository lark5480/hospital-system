<script setup lang="ts">
import { onActivated, onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { Search, Refresh } from '@element-plus/icons-vue'
import * as pharmacyApi from '@/api/pharmacy'
import { hasAuthority } from '@/stores/auth'
import type { PrescriptionDetail } from '@/types/pharmacy'

const router = useRouter()
const prescriptions = ref<PrescriptionDetail[]>([])
const loading = ref(false)
const filterStatus = ref('')
const keyword = ref('')

const canDispense = computed(() => hasAuthority('pharmacy:dispense'))

const statusMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
  PENDING: { text: '待发药', type: 'warning' },
  DISPENSING: { text: '发药中', type: '' },
  DISPENSED: { text: '已发药', type: 'success' },
  CANCELLED: { text: '已取消', type: 'info' }
}

async function fetchList() {
  loading.value = true
  try {
    prescriptions.value = await pharmacyApi.listPrescriptionsWithDetail(
      filterStatus.value || undefined,
      keyword.value.trim() || undefined
    )
  } catch {
    ElMessage.error('加载处方列表失败')
  } finally {
    loading.value = false
  }
}

async function handleDispense(id: number) {
  try {
    await ElMessageBox.confirm('确认发药?', '发药确认')
  } catch { return }
  try {
    await pharmacyApi.dispensePrescription(id)
    ElMessage.success('发药完成')
    await fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '发药失败')
  }
}

async function handleCancel(id: number) {
  try {
    await ElMessageBox.confirm('确认取消该处方?', '取消确认')
  } catch { return }
  try {
    await pharmacyApi.cancelPrescription(id)
    ElMessage.success('处方已取消')
    await fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '取消失败')
  }
}

onMounted(fetchList)
onActivated(fetchList)
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="keyword"
          placeholder="搜索患者/医生"
          clearable
          style="width:220px"
          @clear="fetchList"
          @keyup.enter="fetchList"
        />
        <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:140px" @change="fetchList">
          <el-option label="待发药" value="PENDING" />
          <el-option label="已发药" value="DISPENSED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" @click="fetchList">
          <el-icon><Search /></el-icon>
          搜索
        </el-button>
        <el-button @click="fetchList">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-table :data="prescriptions" v-loading="loading" border stripe>
      <el-table-column label="处方号" width="100">
        <template #default="{ row }">{{ row.prescription.id }}</template>
      </el-table-column>
      <el-table-column label="就诊ID" width="80">
        <template #default="{ row }">{{ row.prescription.visitId }}</template>
      </el-table-column>
      <el-table-column label="患者" width="100">
        <template #default="{ row }">{{ row.patientName || row.prescription.patientId }}</template>
      </el-table-column>
      <el-table-column label="医生" width="100">
        <template #default="{ row }">{{ row.doctorName || row.prescription.doctorId }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.prescription.status].type">{{ statusMeta[row.prescription.status].text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ row.prescription.createdAt }}</template>
      </el-table-column>
      <el-table-column label="发药时间" width="180">
        <template #default="{ row }">{{ row.prescription.dispensedAt }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" @click="router.push('/pharmacy/prescriptions/' + row.prescription.id)">详情</el-button>
          <el-button v-if="row.prescription.status === 'PENDING' && canDispense" size="small" type="primary" @click="handleDispense(row.prescription.id)">发药</el-button>
          <el-button v-if="row.prescription.status === 'PENDING' && canDispense" size="small" type="danger" @click="handleCancel(row.prescription.id)">取消</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>