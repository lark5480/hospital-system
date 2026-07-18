<script setup lang="ts">
import { onActivated, onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as chargeApi from '@/api/charge'
import type { ChargeVO } from '@/api/charge'

const loading = ref(false)
const activeTab = ref('unpaid')
const unpaidList = ref<ChargeVO[]>([])
const paidList = ref<ChargeVO[]>([])

const groupedUnpaid = computed(() => groupByVisit(unpaidList.value))
const groupedPaid = computed(() => groupByVisit(paidList.value))

function groupByVisit(items: ChargeVO[]) {
  const map = new Map<number, { visitId: number; patientName: string; items: ChargeVO[]; total: number }>()
  for (const c of items) {
    const vid = c.charge.visitId
    if (!map.has(vid)) {
      map.set(vid, { visitId: vid, patientName: c.patientName || '', items: [], total: 0 })
    }
    const g = map.get(vid)!
    g.items.push(c)
    g.total += c.charge.amount
  }
  return Array.from(map.values())
}

async function fetchUnpaid() {
  try {
    unpaidList.value = await chargeApi.listUnpaidCharges()
  } catch { ElMessage.error('加载待收费记录失败') }
}

async function fetchPaid() {
  try {
    paidList.value = await chargeApi.listPaidCharges()
  } catch { ElMessage.error('加载已收费记录失败') }
}

async function fetchAll() {
  loading.value = true
  await Promise.all([fetchUnpaid(), fetchPaid()])
  loading.value = false
}

async function handlePay(visitId: number) {
  try {
    await ElMessageBox.confirm(`确认结算就诊 #${visitId} 的全部未付费用？`, '收费确认')
    await chargeApi.payVisitCharges(visitId)
    ElMessage.success(`就诊 #${visitId} 结算成功`)
    await fetchAll()
  } catch { /* cancelled or error */ }
}

onMounted(fetchAll)
onActivated(fetchAll)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>收费管理</h2>
      <el-button type="primary" @click="fetchAll" :loading="loading">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab" class="tabs">
      <el-tab-pane label="待收费" name="unpaid">
        <template v-if="groupedUnpaid.length === 0 && !loading">
          <el-empty description="暂无待收费记录" />
        </template>
        <el-card v-for="g in groupedUnpaid" :key="g.visitId" shadow="hover" class="card">
          <template #header>
            <div class="card-header">
              <span>
                <strong>就诊 #{{ g.visitId }}</strong>
                <el-tag type="warning" style="margin-left:8px">未结算</el-tag>
              </span>
              <span class="card-info">患者: {{ g.patientName || '-' }} ｜ 合计: ¥{{ g.total.toFixed(2) }}</span>
              <el-button type="success" @click="handlePay(g.visitId)">结算</el-button>
            </div>
          </template>
          <el-table :data="g.items" border stripe size="small">
            <el-table-column label="项目" min-width="160">
              <template #default="{ row }">{{ row.charge.itemName }}</template>
            </el-table-column>
            <el-table-column label="金额" width="120">
              <template #default="{ row }">¥{{ row.charge.amount.toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default><el-tag type="warning" size="small">未付</el-tag></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="已收费" name="paid">
        <template v-if="groupedPaid.length === 0 && !loading">
          <el-empty description="暂无已收费记录" />
        </template>
        <el-card v-for="g in groupedPaid" :key="g.visitId" shadow="hover" class="card">
          <template #header>
            <div class="card-header">
              <span>
                <strong>就诊 #{{ g.visitId }}</strong>
                <el-tag type="success" style="margin-left:8px">已结算</el-tag>
              </span>
              <span class="card-info">患者: {{ g.patientName || '-' }} ｜ 合计: ¥{{ g.total.toFixed(2) }}</span>
            </div>
          </template>
          <el-table :data="g.items" border stripe size="small">
            <el-table-column label="项目" min-width="160">
              <template #default="{ row }">{{ row.charge.itemName }}</template>
            </el-table-column>
            <el-table-column label="金额" width="120">
              <template #default="{ row }">¥{{ row.charge.amount.toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="付款时间" width="180">
              <template #default="{ row }">{{ row.charge.payTime || '-' }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; }
.tabs { margin-top: 0; }
.card { margin-bottom: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.card-info { color: #666; font-size: 14px; }
</style>
