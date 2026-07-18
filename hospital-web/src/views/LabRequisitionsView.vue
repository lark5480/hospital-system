<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as labApi from '@/api/lab'
import type { LabRequisition } from '@/types/lab'

const router = useRouter()
const list = ref<LabRequisition[]>([])
const loading = ref(false)
const filterStatus = ref('')

const statusMeta: Record<string, string> = {
  PENDING: 'warning', EXECUTED: 'success', CANCELLED: 'info'
}
const statusText: Record<string, string> = {
  PENDING: '待检验', EXECUTED: '已完成', CANCELLED: '已取消'
}

async function fetchList() {
  loading.value = true
  try {
    list.value = await labApi.listRequisitions(filterStatus.value || undefined)
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

onMounted(fetchList)
onActivated(fetchList)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>检验申请</h2>
      <div class="toolbar-right">
        <el-button :icon="Refresh" @click="fetchList" title="刷新" />
        <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:140px" @change="fetchList">
        <el-option label="待检验" value="PENDING" />
        <el-option label="已完成" value="EXECUTED" />
      </el-select>
      </div>
    </div>
    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="id" label="申请号" width="100" />
      <el-table-column prop="visitId" label="就诊ID" width="80" />
      <el-table-column prop="patientId" label="患者ID" width="80" />
      <el-table-column prop="doctorId" label="医生ID" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status] || ''">{{ statusText[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column prop="reportedAt" label="报告时间" width="180" />
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button size="small" @click="router.push('/lab/requisitions/' + row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }
</style>
