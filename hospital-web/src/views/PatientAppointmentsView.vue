<script setup lang="ts">
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePatientStore } from '@/stores/patient'
import type { AppointmentDetail } from '@/types/patient'

const store = usePatientStore()

const statusMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
  BOOKED: { text: '已预约', type: 'info' },
  CHECKED_IN: { text: '已到院', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' }
}

const payMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
  PAID: { text: '已支付', type: 'success' },
  UNPAID: { text: '未支付', type: 'danger' },
  // 取消预约时由 PAID 转来:当前无支付网关,建单即标记 PAID,取消必须同步改状态,
  // 否则会出现"已支付 + 已取消"的矛盾展示
  REFUNDED: { text: '已退款', type: 'info' }
}

/** 非「已预约」状态下,不可用按钮的悬浮说明(与后端状态校验规则保持一致)。 */
const cancelDisabledReason: Record<string, string> = {
  CHECKED_IN: '您已到院签到,不可自助取消,请联系前台办理',
  DONE: '本次体检已完成,不可取消',
  CANCELLED: '该预约已取消,请勿重复操作'
}

function isCancellable(status?: string) {
  return status === 'BOOKED'
}

async function handleCancel(row: AppointmentDetail) {
  try {
    await ElMessageBox.confirm(
      '确认取消该预约?取消后将释放该号源,名额可被他人预约,且无法恢复。',
      '取消预约确认',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '再想想' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await store.cancel(row.id)
    ElMessage.success('预约已取消,号源已释放')
  } catch (e: any) {
    // 透出后端返回的真实原因(如"已到院不可自助取消"),不要只给一个笼统的"操作失败"
    ElMessage.error(e?.response?.data?.message || e?.message || '取消预约失败')
  }
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
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
          <el-button
            v-if="isCancellable(row.status)"
            type="danger"
            link
            @click="handleCancel(row)"
          >
            取消预约
          </el-button>
          <!-- 已到院/已完成/已取消:给一个不可点的灰按钮 + tooltip 说明原因,比直接空白更易理解 -->
          <el-tooltip
            v-else
            :content="cancelDisabledReason[row.status as keyof typeof cancelDisabledReason] ?? '当前状态不支持取消'"
            placement="top"
          >
            <span>
              <el-button type="info" link disabled>取消预约</el-button>
            </span>
          </el-tooltip>
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
.toolbar h2 {
  margin: 0;
  font-size: 18px;
}
</style>
