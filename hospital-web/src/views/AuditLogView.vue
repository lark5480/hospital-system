<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchAuditLogs, type AuditLogItem } from '@/api/audit'

const logs = ref<AuditLogItem[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const filterAction = ref('')
const filterActor = ref('')

const actionOptions = [
  'CREATE_VISIT', 'CONFIRM_VISIT', 'FINISH_VISIT', 'DELETE_VISIT',
  'EDIT_ORDER', 'CANCEL_ORDER', 'REFUND_ORDER', 'EXECUTE_EXAM_ORDER',
  'PAY_CHARGE', 'CREATE_PRESCRIPTION', 'DISPENSE', 'CANCEL_PRESCRIPTION',
  'CREATE_REQUISITION', 'SUBMIT_RESULTS', 'CANCEL_REQUISITION',
  'BOOK_APPOINTMENT', 'CANCEL_APPOINTMENT', 'CREATE_REPORT', 'PUBLISH_REPORT',
  'REGISTER', 'CALL_NEXT', 'CANCEL_REGISTRATION',
  'DISPATCH_START', 'DISPATCH_COMPLETE', 'DISPATCH_CALL_NEXT',
  'DISPATCH_SKIP', 'DISPATCH_REQUEUE', 'DISPATCH_REORDER',
  'CREATE_STAFF', 'UPDATE_STAFF', 'DELETE_STAFF',
  'CREATE_DEPT', 'UPDATE_DEPT', 'DELETE_DEPT',
  'CREATE_MENU', 'UPDATE_MENU', 'DELETE_MENU', 'SORT_MENU',
  'SAVE_ROLE_AUTHORITIES', 'SAVE_MEDICAL_RECORD', 'CHANGE_PASSWORD'
]

async function load() {
  loading.value = true
  try {
    const res = await fetchAuditLogs({
      action: filterAction.value || undefined,
      actor: filterActor.value || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    logs.value = res.items
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>操作审计</h2>
      <div class="filters">
        <el-select v-model="filterAction" placeholder="操作类型" clearable style="width: 200px" @change="search">
          <el-option v-for="a in actionOptions" :key="a" :label="a" :value="a" />
        </el-select>
        <el-input v-model="filterActor" placeholder="操作人" clearable style="width: 150px"
          @keyup.enter="search" @clear="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>
    </div>

    <el-table :data="logs" border stripe v-loading="loading" empty-text="暂无审计记录">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="actor" label="操作人" width="120" />
      <el-table-column prop="action" label="操作类型" width="180">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.action }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="target" label="操作目标" min-width="140" />
      <el-table-column prop="detail" label="操作明细" min-width="180">
        <template #default="{ row }">{{ row.detail || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="180" />
    </el-table>

    <el-pagination
      v-if="total > pageSize"
      class="pagination"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNum"
      @current-change="(p: number) => { pageNum = p; load() }"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar h2 { margin: 0; font-size: 18px; }
.filters { display: flex; gap: 8px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
</style>
