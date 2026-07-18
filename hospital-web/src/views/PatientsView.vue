<script setup lang="ts">
import { onActivated, onMounted, reactive, ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as patientApi from '@/api/patient'
import type { Patient } from '@/types/patient'
import { hasAuthority } from '@/stores/auth'

const list = ref<Patient[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const canAdmin = computed(() => hasAuthority('system:admin'))

const createDialog = ref(false)
const editDialog = ref(false)
const submitting = ref(false)

const form = reactive({ id: 0, name: '', gender: 'M', birthday: '', phone: '', idCard: '', username: '' })

const rules = reactive<FormRules>({
  name: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请填写手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  idCard: [
    { pattern: /^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/, message: '身份证号格式不正确', trigger: 'blur' }
  ]
})

/** 身份证号变化时,若生日为空则自动从身份证截取 */
watch(() => form.idCard, (val) => {
  if (val && /^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/.test(val)) {
    if (!form.birthday) {
      const y = val.substring(6, 10)
      const m = val.substring(10, 12)
      const d = val.substring(12, 14)
      form.birthday = `${y}-${m}-${d}`
    }
  }
})

const formRef = ref<FormInstance>()
const editFormRef = ref<FormInstance>()

async function fetchList() {
  loading.value = true
  try {
    list.value = searchKeyword.value
      ? await patientApi.searchPatients(searchKeyword.value)
      : await patientApi.listPatients()
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function resetForm() {
  form.id = 0; form.name = ''; form.gender = 'M'; form.birthday = ''
  form.phone = ''; form.idCard = ''; form.username = ''
}

function openEdit(row: Patient) {
  Object.assign(form, {
    id: row.id, name: row.name, gender: row.gender,
    birthday: row.birthday || '', phone: row.phone || '',
    idCard: row.idCard || '', username: row.username || ''
  })
  editDialog.value = true
}

async function submitCreate() {
  if (!formRef.value) return
  await formRef.value.validate((valid) => {
    if (!valid) return
    doCreate()
  })
}

async function doCreate() {
  submitting.value = true
  try {
    await patientApi.registerPatient({
      name: form.name, gender: form.gender, birthday: form.birthday,
      phone: form.phone, idCard: form.idCard, username: form.username
    })
    ElMessage.success('患者已建档')
    createDialog.value = false
    resetForm()
    await fetchList()
  } catch { ElMessage.error('建档失败') }
  finally { submitting.value = false }
}

async function submitEdit() {
  if (!editFormRef.value) return
  await editFormRef.value.validate((valid) => {
    if (!valid) return
    doEdit()
  })
}

async function doEdit() {
  submitting.value = true
  try {
    await patientApi.updatePatient(form.id, {
      name: form.name, gender: form.gender, birthday: form.birthday,
      phone: form.phone, idCard: form.idCard, username: form.username
    })
    ElMessage.success('患者信息已更新')
    editDialog.value = false
    await fetchList()
  } catch { ElMessage.error('更新失败') }
  finally { submitting.value = false }
}

async function handleResetPassword(row: Patient) {
  if (!row.userId) {
    ElMessage.warning('该患者未关联登录账号，无法重置密码')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认将患者 "${row.name}" 的密码重置为 123456？`,
      '重置密码',
      { confirmButtonText: '确认重置', cancelButtonText: '取消', type: 'warning' }
    )
    await patientApi.resetPatientPassword(row.userId)
    ElMessage.success('密码已重置为 123456')
  } catch { /* cancelled */ }
}

onMounted(fetchList)
// KeepAlive 激活时刷新,保证从详情/编辑页返回列表是最新的
onActivated(fetchList)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>患者管理</h2>
      <div>
        <el-input v-model="searchKeyword" placeholder="搜索姓名/手机号" clearable style="width:200px;margin-right:8px" @clear="fetchList" @keyup.enter="fetchList" />
        <el-button type="primary" @click="fetchList">搜索</el-button>
        <el-button :icon="Refresh" @click="fetchList" title="刷新" />
        <el-button type="success" @click="createDialog = true; resetForm">+ 新建患者</el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border stripe empty-text="暂无患者">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column label="性别" width="60"><template #default="{ row }">{{ row.gender === 'M' ? '男' : '女' }}</template></el-table-column>
      <el-table-column prop="birthday" label="出生日期" width="120" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column prop="idCard" label="身份证号" width="180" />
      <el-table-column prop="username" label="关联账号" width="120" />
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canAdmin && row.userId" size="small" type="warning" @click="handleResetPassword(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createDialog" title="新建患者" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="姓名" prop="name"><el-input v-model="form.name" placeholder="患者姓名" /></el-form-item>
        <el-form-item label="性别" prop="gender"><el-select v-model="form.gender" style="width:100%"><el-option label="男" value="M" /><el-option label="女" value="F" /></el-select></el-form-item>
        <el-form-item label="出生日期" prop="birthday"><el-date-picker v-model="form.birthday" type="date" value-format="YYYY-MM-DD" placeholder="选择出生日期" style="width:100%" :disabled-date="(d: Date) => d > new Date()" format="YYYY年MM月DD日" /></el-form-item>
        <el-form-item label="手机号" prop="phone"><el-input v-model="form.phone" placeholder="11位手机号" maxlength="11" /></el-form-item>
        <el-form-item label="身份证" prop="idCard"><el-input v-model="form.idCard" placeholder="18位(选填,填后自动填生日)" maxlength="18" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="form.username" placeholder="用户名(选填,默认手机号)" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialog" title="编辑患者" width="480px">
      <el-form ref="editFormRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="姓名" prop="name"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="性别" prop="gender"><el-select v-model="form.gender" style="width:100%"><el-option label="男" value="M" /><el-option label="女" value="F" /></el-select></el-form-item>
        <el-form-item label="出生日期" prop="birthday"><el-date-picker v-model="form.birthday" type="date" value-format="YYYY-MM-DD" placeholder="选择出生日期" style="width:100%" :disabled-date="(d: Date) => d > new Date()" format="YYYY年MM月DD日" /></el-form-item>
        <el-form-item label="手机号" prop="phone"><el-input v-model="form.phone" placeholder="11位手机号" maxlength="11" /></el-form-item>
        <el-form-item label="身份证" prop="idCard"><el-input v-model="form.idCard" placeholder="18位(选填)" maxlength="18" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="form.username" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; }
</style>