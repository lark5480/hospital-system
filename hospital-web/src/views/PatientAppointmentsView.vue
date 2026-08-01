<script setup lang="ts">
import { onMounted } from 'vue'
import { usePatientStore } from '@/stores/patient'

const store = usePatientStore()

const statusMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
  BOOKED: { text: '已预约', type: 'info' },
  CHECKED_IN: { text: '已到院', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' }
}

const payMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
  PAID: { text: '已支付', type: 'success' },
  UNPAID: { text: '未支付', type: 'danger' }
}

onMounted(async () => {
  store.fetchMyAppointments()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>我的预约(C端)</h2>
    </div>

    <el-table
      :data="store.appointments"
      v-loading="store.loading"
      border
      stripe
      empty-text="暂无预约记录"
    >
      <el-table-column prop="id" label="预约ID" width="90" />
      <el-table-column prop="packageName" label="套餐" min-width="140" />
      <el-table-column prop="examDate" label="体检日期" width="130" />
      <el-table-column prop="period" label="时段" width="90" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status as keyof typeof statusMeta]?.type">
            {{ statusMeta[row.status as keyof typeof statusMeta]?.text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="付费" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.payStatus" :type="payMeta[row.payStatus]?.type" size="small">
            {{ payMeta[row.payStatus]?.text ?? row.payStatus }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="payAmount" label="金额(元)" width="100">
        <template #default="{ row }">{{ row.payAmount?.toFixed(2) ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="预约时间" min-width="180" />
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
.toolbar h2 {
  margin: 0;
  font-size: 18px;
}
</style>
