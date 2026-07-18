<script setup lang="ts">
import { onActivated, onMounted, onBeforeUnmount, reactive, ref, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { useVisitStore } from '@/stores/visit'
import { hasAuthority } from '@/stores/auth'
import { searchPatients, registerPatient } from '@/api/patient'
import { getCurrentStaff, listStaff } from '@/api/org'
import { listDepartments } from '@/api/org'
import { listReportsByPatient } from '@/api/report'
import type { VisitCreatePayload, VisitStatus } from '@/types/visit'
import type { Patient } from '@/types/patient'
import type { Staff, Department } from '@/types/org'
import type { ReportDetail } from '@/types/report'

const router = useRouter()
const store = useVisitStore()

const dialogVisible = ref(false)
const submitting = ref(false)

// 四权·录入权:无 visit:entry 角色时禁用"新建就诊"(与后端 @PreAuthorize 同源校验)。
const canEntry = computed(() => hasAuthority('visit:entry'))

const form = reactive<VisitCreatePayload>({
  patientId: 0,
  doctorId: 0,
  deptId: 0,
  chiefComplaint: ''
})

// ---- 科室 + 医生选择器 ----
const departments = ref<Department[]>([])
const doctors = ref<Staff[]>([])
const filteredDoctors = ref<Staff[]>([])
const selectedDeptName = ref('')
const selectedDoctorName = ref('')
const currentStaff = ref<Staff | null>(null)
const isDoctorUser = ref(false)

async function loadDepartments() {
  try {
    departments.value = await listDepartments()
  } catch { /* ignore */ }
}

async function loadDoctors() {
  try {
    doctors.value = await listStaff('DOCTOR')
  } catch { /* ignore */ }
}

// 当科室变化时过滤医生
watch(() => form.deptId, (newDeptId) => {
  if (newDeptId) {
    filteredDoctors.value = doctors.value.filter(d => d.deptId === newDeptId)
    const dept = departments.value.find(d => d.id === newDeptId)
    selectedDeptName.value = dept?.name || ''
  } else {
    filteredDoctors.value = [...doctors.value]
    selectedDeptName.value = ''
  }
})

// ---- 患者搜索 ----
const searchKeyword = ref('')
const searchResults = ref<Patient[]>([])
const searching = ref(false)
const selectedPatient = ref<Patient | null>(null)

// 患者搜索下拉框定位:挂到 body 并以 fixed 跟随输入框,避免被弹窗 body 的 overflow 裁剪/溢出。
// 用响应式位置 + nextTick 重新测量,确保弹窗布局稳定后坐标正确,并在滚动/缩放时实时更新。
const patientSelectRef = ref<HTMLElement>()
const dropdownPos = reactive({ top: '0px', left: '0px', width: '0px' })
const dropdownStyle = computed(() => ({
  position: 'fixed' as const,
  top: dropdownPos.top,
  left: dropdownPos.left,
  width: dropdownPos.width,
  zIndex: '3000'
}))
let positionListenersAttached = false

function updateDropdownPos() {
  const el = patientSelectRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  dropdownPos.top = `${rect.bottom + 4}px`
  dropdownPos.left = `${rect.left}px`
  dropdownPos.width = `${rect.width}px`
}

function ensurePositionListeners() {
  if (positionListenersAttached) return
  positionListenersAttached = true
  window.addEventListener('scroll', updateDropdownPos, true)
  window.addEventListener('resize', updateDropdownPos)
}

function removePositionListeners() {
  if (!positionListenersAttached) return
  positionListenersAttached = false
  window.removeEventListener('scroll', updateDropdownPos, true)
  window.removeEventListener('resize', updateDropdownPos)
}

watch(
  () => searchResults.value.length,
  (len) => {
    if (len > 0) {
      updateDropdownPos()
      nextTick(updateDropdownPos)
      ensurePositionListeners()
    } else {
      removePositionListeners()
    }
  }
)

let searchTimer: ReturnType<typeof setTimeout> | null = null
function handleSearch(keyword: string) {
  searchKeyword.value = keyword
  if (searchTimer) clearTimeout(searchTimer)
  if (!keyword.trim()) {
    searchResults.value = []
    return
  }
  searchTimer = setTimeout(async () => {
    searching.value = true
    try {
      searchResults.value = await searchPatients(keyword.trim())
    } catch {
      searchResults.value = []
    } finally {
      searching.value = false
    }
  }, 300)
}

function selectPatient(patient: Patient) {
  selectedPatient.value = patient
  form.patientId = patient.id
  searchKeyword.value = `${patient.name} (${patient.phone})`
  searchResults.value = []
  loadHistoryReports(patient.id)
}

function clearPatient() {
  selectedPatient.value = null
  form.patientId = 0
  searchKeyword.value = ''
  searchResults.value = []
  historyReports.value = []
}

// 加载该患者已发布的检验/检查历史报告
async function loadHistoryReports(patientId: number) {
  historyLoading.value = true
  try {
    historyReports.value = await listReportsByPatient(patientId)
  } catch { historyReports.value = [] }
  finally { historyLoading.value = false }
}

function viewHistoryReport(report: ReportDetail) {
  selectedHistoryReport.value = report
  historyDetailDialog.value = true
}

// ---- 新建患者 ----
const createDialogVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  name: '',
  phone: '',
  gender: '' as '' | 'MALE' | 'FEMALE'
})

async function createPatient() {
  if (!createForm.name.trim() || !createForm.phone.trim()) {
    ElMessage.warning('请填写姓名和手机号')
    return
  }
  creating.value = true
  try {
    const res = await registerPatient({
      name: createForm.name.trim(),
      phone: createForm.phone.trim(),
      gender: createForm.gender || undefined
    })
    createDialogVisible.value = false
    createForm.name = ''
    createForm.phone = ''
    createForm.gender = ''
    selectPatient(res.patient)
    if (res.tempPassword) {
      await ElMessageBox.alert(
        `登录账号：${res.username}\n初始密码：${res.tempPassword}\n（首次登录需修改密码）`,
        '患者 C 端账号已开通',
        { type: 'success', confirmButtonText: '知道了', customClass: 'patient-account-alert' }
      )
    } else {
      ElMessage.info('患者已存在，未重复开通账号')
    }
  } catch {
    ElMessage.error('创建失败')
  } finally {
    creating.value = false
  }
}

const statusMeta: Record<VisitStatus, { text: string; type: '' | 'success' | 'warning' | 'info' }> = {
  CREATED: { text: '草稿', type: 'info' },
  CONFIRMED: { text: '已确单', type: 'warning' },
  IN_PROGRESS: { text: '进行中', type: '' },
  FINISHED: { text: '已完成', type: 'success' }
}

// ---- 患者历史报告(检验/检查) ----
const historyReports = ref<ReportDetail[]>([])
const historyLoading = ref(false)
const historyDetailDialog = ref(false)
const selectedHistoryReport = ref<ReportDetail | null>(null)
function goDetail(id: number) {
  router.push(`/visits/${id}`)
}

async function handleDelete(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该草稿就诊单?此操作不可恢复。', '删除确认', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await store.deleteVisit(id)
    ElMessage.success('已删除')
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
  }
}

async function openCreateDialog() {
  clearPatient()
  form.doctorId = 0
  form.deptId = 0
  form.chiefComplaint = ''
  selectedDeptName.value = ''
  selectedDoctorName.value = ''
  currentStaff.value = null
  isDoctorUser.value = false

  // 先加载科室 + 医生列表(后续自动填充需匹配 label)
  await Promise.all([loadDepartments(), loadDoctors()])

  // 尝试取当前登录账号的员工信息
  try {
    const staff = await getCurrentStaff()
    currentStaff.value = staff
    if (staff && staff.position === 'DOCTOR') {
      // 医生自己建就诊 → 自动填充(此时 departments 已加载,可正确匹配 label)
      isDoctorUser.value = true
      form.doctorId = staff.id
      form.deptId = staff.deptId
      const dept = departments.value.find(d => d.id === staff.deptId)
      selectedDeptName.value = dept?.name || ''
    }
  } catch {
    // 非 staff 角色(如 patient、admin),走手动选择
  }
  dialogVisible.value = true
}

async function submit() {
  if (form.deptId === 0) { ElMessage.warning('请选择科室'); return }
  if (form.doctorId === 0) { ElMessage.warning('请选择医生'); return }
  if (!form.chiefComplaint.trim()) { ElMessage.warning('请填写主诉'); return }
  submitting.value = true
  try {
    const payload = { ...form }
    const newVisitId = (await store.create(payload)).id
    ElMessage.success('已创建并进入详情')
    dialogVisible.value = false
    form.chiefComplaint = ''
    if (newVisitId) {
      router.push('/visits/' + newVisitId)
    }
  } catch {
    ElMessage.error('创建失败')
  } finally {
    submitting.value = false
  }
}

onMounted(store.fetchList)
onActivated(store.fetchList)
onBeforeUnmount(removePositionListeners)
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="store.keyword"
          placeholder="搜索患者/医生/主诉"
          clearable
          style="width:260px"
          @clear="store.fetchList()"
          @keyup.enter="store.fetchList()"
        />
        <el-button type="primary" @click="store.fetchList()">
          <el-icon><Search /></el-icon>
          搜索
        </el-button>
        <el-button @click="store.fetchList()">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
      <el-button type="primary" :disabled="!canEntry" @click="openCreateDialog">+ 新建就诊</el-button>
    </div>

    <el-table :data="store.visits" v-loading="store.loading" border stripe empty-text="暂无就诊记录">
      <el-table-column label="ID" width="80">
        <template #default="{ row }">{{ row.visit.id }}</template>
      </el-table-column>
      <el-table-column label="患者" width="120">
        <template #default="{ row }">{{ row.patientName || row.visit.patientId }}</template>
      </el-table-column>
      <el-table-column label="医生" width="100">
        <template #default="{ row }">{{ row.doctorName || row.visit.doctorId }}</template>
      </el-table-column>
      <el-table-column label="科室" width="100">
        <template #default="{ row }">{{ row.deptName || row.visit.deptId }}</template>
      </el-table-column>
      <el-table-column label="主诉" min-width="160">
        <template #default="{ row }">{{ row.visit.chiefComplaint }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.visit.status as VisitStatus].type" size="small">
            {{ statusMeta[row.visit.status as VisitStatus].text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="收费" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.payStatus === 'HAS_UNPAID'" type="danger" size="small">有待收</el-tag>
          <el-tag v-else-if="row.payStatus === 'ALL_PAID'" type="success" size="small">已收清</el-tag>
          <el-tag v-else type="info" size="small">无费用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="就诊时间" min-width="180">
        <template #default="{ row }">{{ row.visit.visitTime }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="goDetail(row.visit.id)">详情</el-button>
          <el-button v-if="row.visit.status === 'CREATED'" type="danger" link @click="handleDelete(row.visit.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      style="margin-top:16px;justify-content:flex-end"
      v-model:current-page="store.pageNum"
      v-model:page-size="store.pageSize"
      :page-sizes="[10,20,50]"
      :total="store.total"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="store.fetchList()"
      @current-change="store.fetchList()"
    />

    <el-dialog v-model="dialogVisible" title="新建就诊" width="520px">
      <el-form :model="form" label-width="80px">
        <!-- 当前是医生 → 显示科室/医生名(只读);非医生 → 显示选择器 -->
        <template v-if="isDoctorUser && currentStaff">
          <el-form-item label="科室">
            <el-input :model-value="selectedDeptName" disabled />
          </el-form-item>
          <el-form-item label="医生">
            <el-input :model-value="currentStaff.name" disabled />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="科室">
            <el-select v-model="form.deptId" placeholder="请选择科室" style="width:100%">
              <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="医生">
            <el-select v-model="form.doctorId" placeholder="请选择医生" style="width:100%" :disabled="!form.deptId">
              <el-option v-for="d in filteredDoctors" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="患者">
          <div class="patient-select" ref="patientSelectRef">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索患者姓名/手机号"
              :disabled="!!selectedPatient"
              @input="handleSearch"
            >
              <template #append>
                <el-button v-if="selectedPatient" @click="clearPatient">清除</el-button>
                <el-button v-else @click="createDialogVisible = true">新建</el-button>
              </template>
            </el-input>
            <template v-if="searchResults.length > 0 && !selectedPatient">
              <teleport to="body">
                <div class="search-dropdown" :style="dropdownStyle">
                  <div
                    v-for="p in searchResults"
                    :key="p.id"
                    class="search-item"
                    @click="selectPatient(p)"
                  >
                    <span class="search-item-name">{{ p.name }}</span>
                    <span class="search-item-phone">{{ p.phone }}</span>
                  </div>
                </div>
              </teleport>
            </template>
            <div v-else-if="searching && !selectedPatient" class="search-hint">搜索中...</div>
            <div v-else-if="!selectedPatient && searchKeyword && searchResults.length === 0 && !searching" class="search-hint">未找到匹配患者</div>
          </div>
        </el-form-item>
        <el-form-item label="主诉">
          <el-input
            v-model="form.chiefComplaint"
            type="textarea"
            :rows="2"
            placeholder="例如:发热 38.5℃,咳嗽"
          />
        </el-form-item>
        <!-- 该患者历史检验/检查报告 -->
        <el-form-item v-if="selectedPatient" label="历史报告">
          <div style="width:100%">
            <el-button text type="primary" size="small" :loading="historyLoading">
              该患者历史报告 ({{ historyReports.length }})
            </el-button>
            <el-tag v-if="!historyLoading && historyReports.length === 0" size="small" type="info" style="margin-left:8px">暂无历史报告</el-tag>
            <el-table v-else :data="historyReports" size="small" border style="margin-top:8px" max-height="200">
              <el-table-column label="类型" width="70">
                <template #default="{ row }">{{ row.type === 'LAB' ? '检验' : '检查' }}</template>
              </el-table-column>
              <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
              <el-table-column label="发布时间" width="150">
                <template #default="{ row }">{{ row.publishedAt || row.createdAt }}</template>
              </el-table-column>
              <el-table-column label="操作" width="60">
                <template #default="{ row }">
                  <el-button type="primary" link size="small" @click="viewHistoryReport(row)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedPatient || !form.deptId || !form.doctorId" :loading="submitting" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 历史报告详情对话框 -->
    <el-dialog v-model="historyDetailDialog" title="历史报告详情" width="600px">
      <template v-if="selectedHistoryReport">
        <el-descriptions border :column="2">
          <el-descriptions-item label="标题">{{ selectedHistoryReport.title }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ selectedHistoryReport.type === 'LAB' ? '检验' : '检查' }}</el-descriptions-item>
          <el-descriptions-item label="患者">{{ selectedHistoryReport.patientName || selectedHistoryReport.patientId }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ selectedHistoryReport.publishedAt || selectedHistoryReport.createdAt }}</el-descriptions-item>
        </el-descriptions>
        <div style="margin-top:16px">
          <h4 style="margin:0 0 8px;font-size:14px">报告内容</h4>
          <div style="background:#f5f7fa;padding:12px;border-radius:4px;white-space:pre-wrap;font-size:13px;line-height:1.6;max-height:260px;overflow-y:auto">
            {{ selectedHistoryReport.content || '(无内容)' }}
          </div>
        </div>
      </template>
      <template #footer>
        <el-button @click="historyDetailDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createDialogVisible" title="新建患者" width="400px">
      <el-form :model="createForm" label-width="60px">
        <el-form-item label="姓名">
          <el-input v-model="createForm.name" placeholder="患者姓名" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="createForm.phone" placeholder="手机号" />
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="createForm.gender" placeholder="请选择" clearable>
            <el-option label="男" value="MALE" />
            <el-option label="女" value="FEMALE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createPatient">创建</el-button>
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
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.toolbar h2 {
  margin: 0;
  font-size: 18px;
}
.patient-select {
  position: relative;
  width: 100%;
}
.search-dropdown {
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  max-height: 200px;
  overflow-y: auto;
}
.search-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 14px;
}
.search-item:hover {
  background: #f5f7fa;
}
.search-item-name {
  font-weight: 500;
}
.search-item-phone {
  color: #909399;
}
.search-hint {
  padding: 8px 12px;
  color: #909399;
  font-size: 13px;
}
</style>
