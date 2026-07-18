<script setup lang="ts">
import { onMounted, reactive, ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useVisitStore } from '@/stores/visit'
import { hasAuthority } from '@/stores/auth'
import type { OrderType, PayStatus, Order } from '@/types/visit'
import * as pharmacyApi from '@/api/pharmacy'
import * as labApi from '@/api/lab'
import * as orgApi from '@/api/org'
import { executeExamOrder as apiExecuteExamOrder } from '@/api/visit'

const route = useRoute()
const visitId = Number(route.params.id)
const store = useVisitStore()

const orderDialog = ref(false)
const paying = ref(false)
const creatingRx = ref(false)
const creatingLab = ref(false)
const executingExamOrderId = ref<number | null>(null)

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

// 仅草稿状态(CREATED)可编辑:已确单/进行中/已完成的就诊单不可追加/修改/取消医嘱
const isEditable = computed(() => store.detail?.visit.status === 'CREATED')
// 已完成(FINISHED)就诊单进入只读模式(不展示操作栏)
const isReadOnly = computed(() => store.detail?.visit.status === 'FINISHED')

const hasMedicationOrders = computed(() =>
  store.detail?.orders.some(o => o.type === 'MEDICATION' && o.status === 'CREATED'))
const hasLabOrders = computed(() =>
  store.detail?.orders.some(o => o.type === 'LAB' && o.status === 'CREATED'))
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
const payStatusMeta: Record<PayStatus, { text: string; type: '' | 'success' | 'warning' }> = {
  UNPAID: { text: '未缴', type: 'warning' },
  PAID: { text: '已缴', type: 'success' }
}

async function submitOrder() {
  if (!orderForm.itemName.trim()) {
    ElMessage.warning('请填写医嘱名称')
    return
  }
  try {
    await store.addOrder(visitId, { ...orderForm })
    ElMessage.success('医嘱已添加,收费已生成')
    orderDialog.value = false
    orderForm.itemName = ''
    orderForm.unitPrice = 0
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '添加失败')
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

async function createPrescription() {
  creatingRx.value = true
  try {
    await pharmacyApi.createPrescription({ visitId, doctorId: store.detail!.visit.doctorId })
    ElMessage.success('处方已创建,就诊单已确单锁定(不再可改医嘱);请到药事管理发药、收费管理结算')
    await store.fetchDetail(visitId)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '创建处方失败')
  } finally {
    creatingRx.value = false
  }
}

async function createLabRequisition() {
  creatingLab.value = true
  try {
    await labApi.createRequisition({ visitId, doctorId: store.detail!.visit.doctorId })
    ElMessage.success('检验申请已创建,就诊单已确单锁定(不再可改医嘱);请到医技管理查看')
    await store.fetchDetail(visitId)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '创建申请失败')
  } finally {
    creatingLab.value = false
  }
}


async function executeExamOrder(orderId: number) {
  executingExamOrderId.value = orderId
  try {
    store.detail = await apiExecuteExamOrder(visitId, orderId)
    ElMessage.success('检查已执行')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '执行失败')
  } finally {
    executingExamOrderId.value = null
  }
}

onMounted(() => {
  store.fetchDetail(visitId)
  loadDepartments()
})

// 路由参数变化时(从 /visits/2 切到 /visits/1)重新拉取
watch(() => route.params.id, (newId) => {
  if (newId) store.fetchDetail(Number(newId))
})
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <h2>就诊详情 #{{ visitId }}</h2>
      <!-- 进行中/已完成 = 只读模式,隐藏操作栏 -->
      <div v-if="!isReadOnly" class="actions">
        <el-button type="primary" :disabled="!canEntry || !isEditable" @click="orderDialog = true">+ 追加医嘱</el-button>
        <el-button type="warning" :loading="creatingRx" :disabled="!canEntry || !isEditable || !hasMedicationOrders" @click="createPrescription">创建处方</el-button>
        <el-button type="warning" :loading="creatingLab" :disabled="!canEntry || !isEditable || !hasLabOrders" @click="createLabRequisition">创建检验申请</el-button>
        <el-button type="success" :loading="paying" :disabled="!canPay || store.detail?.visit.status !== 'CONFIRMED'" @click="doPay">结算</el-button>
      </div>
    </div>

    <template v-if="store.detail">
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
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="orderStatusMeta[row.status].type" size="small">{{ orderStatusMeta[row.status].text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button v-if="row.status === 'CREATED' && canEntry && isEditable"
              size="small" type="primary" link
              @click="openEditOrder(row)">修改</el-button>
            <el-button v-if="row.status === 'CREATED' && canEntry && isEditable"
              size="small" type="danger" link
              @click="confirmCancelOrder(row)">取消</el-button>
            <el-button v-if="row.type === 'EXAM' && row.status === 'CREATED' && canExecute"
              size="small" type="success"
              :loading="executingExamOrderId === row.id"
              @click="executeExamOrder(row.id)">执行检查</el-button>
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
  margin-top: 16px;
  text-align: right;
  font-size: 16px;
  font-weight: 600;
  color: #ff5c1a;
}
.hint { font-size: 12px; color: #909399; margin-left: 8px; }
</style>