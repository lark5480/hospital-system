<script setup lang="ts">
import { onMounted, onActivated, onUnmounted, reactive, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useVisitStore } from '@/stores/visit'
import { useAuthStore, hasAuthority } from '@/stores/auth'
import type { OrderType, PayStatus, Order } from '@/types/visit'
import * as orgApi from '@/api/org'
import { executeExamOrder as apiExecuteExamOrder } from '@/api/visit'
import * as labApi from '@/api/lab'
import * as pharmacyApi from '@/api/pharmacy'

const route = useRoute()
const visitId = Number(route.params.id)
const store = useVisitStore()
const authStore = useAuthStore()

const orderDialog = ref(false)
const paying = ref(false)
const confirming = ref(false)
const executingExam = ref(false)
const finishing = ref(false)

// Exam finding dialog state
const examFindingDialog = ref(false)
const examFindingText = ref('')
const pendingExamOrderId = ref<number | null>(null)

// Edit order state
const editDialog = ref(false)
const editingOrder = ref<Order | null>(null)
const editForm = reactive<{ type: OrderType; itemName: string; quantity: number; unitPrice: number }>({
  type: 'MEDICATION',
  itemName: '',
  quantity: 1,
  unitPrice: 0
})

const canEntry = computed(() => hasAuthority('visit:entry'))
const canPay = computed(() => hasAuthority('charge:pay'))
const canExecute = computed(() => hasAuthority('order:execute'))
const canAudit = computed(() => hasAuthority('visit:audit'))

// 多个就诊详情 tab 被 KeepAlive 缓存且共享 store.detail:
// 仅当 store.detail 的 visitId 与本页一致时才视为本页数据,
// 避免切回 tab 瞬间把其他就诊单(如已完成的别单)状态套到本页。
const isOwnDetail = computed(() => !!store.detail && store.detail.visit.id === visitId)

// 是否可以确单：草稿状态 + 有医嘱 + 有visit:entry权限
const canConfirm = computed(() =>
  canEntry.value &&
  store.detail?.visit.status === 'CREATED' &&
  (store.detail?.orders?.length ?? 0) > 0
)

// 是否可以结算：已缴费权限 + 有未缴费用 + (已确单或进行中)
const canSettle = computed(() =>
  canPay.value &&
  hasUnpaidCharges.value &&
  (store.detail?.visit.status === 'CONFIRMED' || store.detail?.visit.status === 'IN_PROGRESS')
)

// 检查当前用户是否可以执行检查（需要order:execute权限 + 有检查医嘱 + 科室匹配 + 已缴费）
const canExecuteExam = computed(() => {
  if (!canExecute.value) return false
  // 如果没有待执行的检查医嘱，不显示
  if (!hasExamOrders.value) return false
  // 获取第一个待执行的检查医嘱
  const examOrder = store.detail?.orders.find(o => o.type === 'EXAM' && o.status === 'CREATED')
  if (!examOrder) return false
  // 检查当前用户科室是否匹配检查医嘱的执行科室
  const currentDeptId = authStore.departmentId
  if (!currentDeptId || !examOrder.executionDeptId) return false
  if (currentDeptId !== examOrder.executionDeptId) return false
  // 检查是否已缴费（所有费用必须已收清）
  if (store.detail?.payStatus !== 'ALL_PAID') return false
  return true
})

// 是否可手动结束就诊:审核权限 + (已确单或进行中)
const canFinish = computed(() =>
  canAudit.value &&
  (store.detail?.visit.status === 'CONFIRMED' || store.detail?.visit.status === 'IN_PROGRESS')
)

// 已完成(FINISHED)就诊单进入只读模式(不展示操作栏)
const isReadOnly = computed(() => store.detail?.visit.status === 'FINISHED')

// 某条医嘱对应收费是否已缴(用于区分取消/退费入口)
function isOrderPaid(orderId: number): boolean {
  return store.detail?.charges.some(c => c.orderId === orderId && c.payStatus === 'PAID') ?? false
}

// 有未缴费项目
const hasUnpaidCharges = computed(() =>
  store.detail?.charges.some(c => c.payStatus === 'UNPAID') ?? false)

const hasExamOrders = computed(() =>
  store.detail?.orders.some(o => o.type === 'EXAM' && o.status === 'CREATED'))
const orderForm = reactive<{ type: OrderType; itemName: string; quantity: number; unitPrice: number; executionDeptId: number }>({
  type: 'MEDICATION',
  itemName: '',
  quantity: 1,
  unitPrice: 0,
  executionDeptId: 0
})

// 执行科室下拉(仅 EXAM/LAB 时使用)
const departments = ref<{ id: number; name: string }[]>([])
async function loadDepartments() {
  try { departments.value = await orgApi.listDepartments() } catch { /* ignore */ }
}
const showExecutionDept = computed(() => ['EXAM', 'LAB'].includes(orderForm.type))

function resolveDeptName(deptId?: number | null): string {
  if (!deptId) return '-'
  return departments.value.find(d => d.id === deptId)?.name || String(deptId)
}

const orderTypeMeta: Record<OrderType, string> = {
  MEDICATION: '药品',
  EXAM: '检查',
  LAB: '检验'
}
const visitStatusMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' }> = {
  CREATED: { text: '草稿', type: 'info' },
  CONFIRMED: { text: '已确单', type: 'warning' },
  IN_PROGRESS: { text: '进行中', type: '' },
  FINISHED: { text: '已完成', type: 'success' }
}
const orderStatusMeta: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' }> = {
  CREATED: { text: '未执行', type: 'info' },
  EXECUTED: { text: '已执行', type: 'success' },
  CANCELLED: { text: '已取消', type: 'warning' }
}
const payStatusMeta: Record<PayStatus, { text: string; type: '' | 'success' | 'warning' | 'info' }> = {
  UNPAID: { text: '未缴', type: 'warning' },
  PAID: { text: '已缴', type: 'success' },
  REFUNDED: { text: '已退', type: 'info' }
}

async function submitOrder() {
  if (!orderForm.itemName.trim()) {
    ElMessage.warning('请填写医嘱名称')
    return
  }
  try {
    await store.addOrder(visitId, { ...orderForm })
    // 如果就诊已确单,自动同步新医嘱到对应单据(支持二次诊断追加)
    if (store.detail && store.detail.visit.status !== 'CREATED') {
      const doctorId = store.detail.visit.doctorId
      if (orderForm.type === 'MEDICATION') {
        try {
          await pharmacyApi.createPrescription({ visitId, doctorId })
        } catch (e: any) {
          if (e?.response?.status !== 409) {
            ElMessage.warning('处方同步失败: ' + (e?.response?.data?.message || e?.message))
          }
        }
      } else if (orderForm.type === 'LAB') {
        try {
          await labApi.createRequisition({ visitId, doctorId })
        } catch (e: any) {
          if (e?.response?.status !== 409) {
            ElMessage.warning('检验申请同步失败: ' + (e?.response?.data?.message || e?.message))
          }
        }
      }
    }
    ElMessage.success('医嘱已添加,收费已生成')
    orderDialog.value = false
    orderForm.itemName = ''
    orderForm.unitPrice = 0
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '添加失败')
    await store.fetchDetail(visitId)  // 刷新状态(如后端拒绝因就诊已结束,前端同步切换只读)
  }
}

function openEditOrder(order: Order) {
  editingOrder.value = order
  editForm.type = order.type
  editForm.itemName = order.itemName
  editForm.quantity = order.quantity
  editForm.unitPrice = order.unitPrice
  editDialog.value = true
}

async function submitEditOrder() {
  if (!editingOrder.value) return
  if (!editForm.itemName.trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  try {
    await store.updateOrder(visitId, editingOrder.value.id, { ...editForm })
    ElMessage.success('医嘱已修改')
    editDialog.value = false
    editingOrder.value = null
  } catch (e: any) {
    ElMessage.error(e?.message || '修改失败')
  }
}

async function confirmCancelOrder(order: Order) {
  try {
    await ElMessageBox.confirm(
      `确认取消该医嘱?同时删除对应未收费记录。`,
      '取消医嘱',
      { confirmButtonText: '确认取消', cancelButtonText: '关闭', type: 'warning' }
    )
    await store.cancelOrder(visitId, order.id)
    ElMessage.success('医嘱已取消')
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '取消失败')
    }
  }
}

async function confirmRefund(order: Order) {
  try {
    await ElMessageBox.confirm(
      `确认对该医嘱退费?医嘱将作废,已缴费用退回。`,
      '退费',
      { confirmButtonText: '确认退费', cancelButtonText: '关闭', type: 'warning' }
    )
    await store.refundOrder(visitId, order.id)
    ElMessage.success('退费成功')
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '退费失败')
    }
  }
}

async function doFinish() {
  // 已缴费未执行的医嘱属下游(药房/检验)职责,结束就诊时保留,无需医生作废
  try {
    await ElMessageBox.confirm(
      '确认结束该就诊?结束后不可再追加或修改医嘱。\n已缴费未执行的医嘱仍可继续发药/检验。',
      '结束就诊',
      { confirmButtonText: '确认结束', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }

  finishing.value = true
  try {
    await store.finishVisit(visitId, false)
    await store.fetchDetail(visitId)
    ElMessage.success('就诊已结束')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '结束失败')
  } finally {
    finishing.value = false
  }
}

async function doPay() {
  paying.value = true
  try {
    await store.pay(visitId)
    await store.fetchDetail(visitId)  // 刷新详情,更新收费状态和就诊状态
    ElMessage.success('结算完成')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '结算失败')
  } finally {
    paying.value = false
  }
}

async function doConfirm() {
  if (!store.detail) return
  const doctorId = store.detail.visit.doctorId
  const hasLabOrders = store.detail.orders.some(o => o.type === 'LAB' && o.status === 'CREATED')
  const hasMedicationOrders = store.detail.orders.some(o => o.type === 'MEDICATION' && o.status === 'CREATED')

  const msgParts: string[] = []
  msgParts.push('确认锁定该就诊单?')
  if (hasLabOrders) msgParts.push('将自动生成检验申请')
  if (hasMedicationOrders) msgParts.push('将自动生成处方')
  msgParts.push('锁定后不可再追加/修改/取消医嘱')

  try {
    await ElMessageBox.confirm(msgParts.join('\n'), '确单', {
      confirmButtonText: '确认确单',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch { return }

  confirming.value = true
  try {
    // 1. 先确单
    await store.confirm(visitId)
    // 2. 自动生成检验申请(如有LAB医嘱)
    if (hasLabOrders) {
      try {
        await labApi.createRequisition({ visitId, doctorId })
      } catch (e: any) {
        // 已有待处理的检验申请则忽略
        if (e?.response?.status !== 409) {
          ElMessage.warning('检验申请生成失败: ' + (e?.response?.data?.message || e?.message))
        }
      }
    }
    // 3. 自动生成处方(如有MEDICATION医嘱)
    if (hasMedicationOrders) {
      try {
        await pharmacyApi.createPrescription({ visitId, doctorId })
      } catch (e: any) {
        // 已有待处理的处方则忽略
        if (e?.response?.status !== 409) {
          ElMessage.warning('处方生成失败: ' + (e?.response?.data?.message || e?.message))
        }
      }
    }
    await store.fetchDetail(visitId)
    ElMessage.success('确单成功')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '确单失败')
  } finally {
    confirming.value = false
  }
}

async function executeFirstExamOrder() {
  const examOrder = store.detail?.orders.find(o => o.type === 'EXAM' && o.status === 'CREATED')
  if (!examOrder) return
  pendingExamOrderId.value = examOrder.id
  examFindingText.value = ''
  examFindingDialog.value = true
}

async function submitExamFinding() {
  if (!pendingExamOrderId.value) return
  executingExam.value = true
  try {
    store.detail = await apiExecuteExamOrder(visitId, pendingExamOrderId.value, examFindingText.value || undefined)
    ElMessage.success('检查已执行')
    examFindingDialog.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '执行失败')
  } finally {
    executingExam.value = false
  }
}

onMounted(() => {
  store.fetchDetail(visitId)
  loadDepartments()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

// KeepAlive 缓存的 tab 切回时重新拉取,确保就诊状态实时(发药/缴费/他端结束后同步)
// 初次挂载由 onMounted 处理,这里跳过避免重复请求
let skipFirstActivate = true
onActivated(() => {
  if (skipFirstActivate) { skipFirstActivate = false; return }
  store.fetchDetail(visitId)
})

// 页面可见性变化时自动刷新(如其他终端已结束就诊,切回时同步只读状态)
function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    store.fetchDetail(visitId)
  }
}

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <h2>就诊详情 #{{ visitId }}</h2>
      <div v-if="isOwnDetail && !isReadOnly" class="actions">
        <el-button type="primary" :disabled="!canEntry || isReadOnly" @click="orderDialog = true">+ 追加医嘱</el-button>
        <el-button v-if="canConfirm" type="warning" :loading="confirming" @click="doConfirm">确单</el-button>
        <el-button v-if="hasExamOrders && canExecuteExam" type="success" :loading="executingExam" @click="executeFirstExamOrder">执行检查</el-button>
        <el-button type="success" :loading="paying" :disabled="!canSettle" @click="doPay">结算</el-button>
        <el-button v-if="canFinish" type="danger" plain :loading="finishing" :disabled="hasUnpaidCharges"
          title="存在未缴费用时需先结算或退费" @click="doFinish">结束就诊</el-button>
      </div>
    </div>

    <!-- store.detail 在加载中/加载失败时为 null,这里显式加上非空守卫,
         使模板内对 store.detail.* 的访问都处于已收窄的作用域中 -->
    <template v-if="store.detail && isOwnDetail">
      <el-descriptions border :column="3" class="block">
        <el-descriptions-item label="患者">{{ store.detail.patientName || store.detail.visit.patientId }}</el-descriptions-item>
        <el-descriptions-item label="医生">{{ store.detail.doctorName || store.detail.visit.doctorId }}</el-descriptions-item>
        <el-descriptions-item label="科室">{{ store.detail.deptName || store.detail.visit.deptId }}</el-descriptions-item>
        <el-descriptions-item label="主诉">{{ store.detail.visit.chiefComplaint }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="visitStatusMeta[store.detail.visit.status]?.type" size="small">
            {{ visitStatusMeta[store.detail.visit.status]?.text || store.detail.visit.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="收费">
          <el-tag v-if="store.detail.payStatus === 'HAS_UNPAID'" type="danger">有待收</el-tag>
          <el-tag v-else-if="store.detail.payStatus === 'ALL_PAID'" type="success">已收清 ¥{{ store.detail.totalAmount }}</el-tag>
          <el-tag v-else type="info">无费用</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="就诊时间">{{ store.detail.visit.visitTime }}</el-descriptions-item>
      </el-descriptions>

      <h3 class="block">医嘱</h3>
      <el-table :data="store.detail.orders" border stripe empty-text="暂无医嘱">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ orderTypeMeta[row.type as OrderType] }}</template>
        </el-table-column>
        <el-table-column prop="itemName" label="名称" min-width="140" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="unitPrice" label="单价" width="100" />
        <el-table-column prop="amount" label="金额" width="100" />
        <el-table-column label="执行科室" width="110"
          v-if="store.detail.orders.some(o => ['EXAM','LAB'].includes(o.type))">
          <template #default="{ row }">
            <span v-if="['EXAM','LAB'].includes(row.type)">{{ resolveDeptName(row.executionDeptId) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="检查所见" min-width="180" v-if="store.detail.orders.some(o => o.type === 'EXAM' && o.finding)">
          <template #default="{ row }">
            <span v-if="row.type === 'EXAM' && row.finding">{{ row.finding }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="orderStatusMeta[row.status].type" size="small">{{ orderStatusMeta[row.status].text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.status === 'CREATED' && canEntry && !isReadOnly && !isOrderPaid(row.id)"
              size="small" type="primary" link
              @click="openEditOrder(row)">修改</el-button>
            <el-button v-if="row.status === 'CREATED' && canEntry && !isReadOnly && !isOrderPaid(row.id)"
              size="small" type="danger" link
              @click="confirmCancelOrder(row)">取消</el-button>
            <el-button v-if="row.status === 'CREATED' && canPay && !isReadOnly && isOrderPaid(row.id)"
              size="small" type="warning" link
              @click="confirmRefund(row)">退费</el-button>
          </template>
        </el-table-column>
      </el-table>

      <h3 class="block">收费</h3>
      <el-table :data="store.detail.charges" border stripe empty-text="暂无收费">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="itemName" label="项目" min-width="140" />
        <el-table-column prop="amount" label="金额" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="payStatusMeta[row.payStatus as PayStatus].type">
              {{ payStatusMeta[row.payStatus as PayStatus].text }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="total">合计: ¥{{ store.detail.totalAmount }}</div>

      <div class="block links">
        <el-button text type="primary" @click="$router.push('/pharmacy/prescriptions')">点击查看处方列表</el-button>
        <el-button text type="primary" @click="$router.push('/lab/requisitions')">点击查看检验申请</el-button>
      </div>
    </template>

    <!-- Add order dialog -->
    <el-dialog v-model="orderDialog" title="追加医嘱" width="500px">
      <el-form :model="orderForm" label-width="80px" >
        <el-form-item label="类型">
          <el-select v-model="orderForm.type" style="width:100%">
            <el-option label="药品" value="MEDICATION" />
            <el-option label="检查" value="EXAM" />
            <el-option label="检验" value="LAB" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="orderForm.itemName" placeholder="如:阿莫西林胶囊" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="orderForm.quantity" :min="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="单价">
          <el-input-number v-model="orderForm.unitPrice" :min="0" :precision="2" :step="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="执行科室">
          <el-select v-model="orderForm.executionDeptId" placeholder="请选择执行科室" style="width:100%"
            :disabled="!showExecutionDept">
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <span v-if="!showExecutionDept" class="hint">仅检查/检验需指定执行科室</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orderDialog = false">取消</el-button>
        <el-button type="primary" @click="submitOrder">提交</el-button>
      </template>
    </el-dialog>

    <!-- Edit order dialog -->
    <el-dialog v-model="editDialog" title="修改医嘱" width="500px">
      <el-form :model="editForm" label-width="80px" >
        <el-form-item label="类型">
          <el-select v-model="editForm.type" style="width:100%">
            <el-option label="药品" value="MEDICATION" />
            <el-option label="检查" value="EXAM" />
            <el-option label="检验" value="LAB" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="editForm.itemName" placeholder="如:阿莫西林胶囊" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="editForm.quantity" :min="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="单价">
          <el-input-number v-model="editForm.unitPrice" :min="0" :precision="2" :step="1" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="submitEditOrder">保存</el-button>
      </template>
    </el-dialog>

    <!-- 执行检查:录入检查所见 -->
    <el-dialog v-model="examFindingDialog" title="执行检查 — 录入检查所见" width="520px">
      <el-form label-width="90px">
        <el-form-item label="检查所见">
          <el-input v-model="examFindingText" type="textarea" :rows="6"
            placeholder="请描述检查所见，如：肝区未见明显异常回声，胆囊壁光滑，胰腺显示清楚..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="examFindingDialog = false">取消</el-button>
        <el-button type="primary" :loading="executingExam" @click="submitExamFinding">确认执行</el-button>
      </template>
    </el-dialog>
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
.toolbar .actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.block {
  margin: 16px 0 8px;
}
.links {
  display: flex;
  gap: 16px;
}
.total {
  margin-top: var(--sp-4);
  text-align: right;
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  color: var(--accent-600);
}
.hint { font-size: var(--fs-xs); color: var(--text-secondary); margin-left: var(--sp-2); }
</style>