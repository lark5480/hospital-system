<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as orgApi from '@/api/org'
import type { Staff, Department } from '@/types/org'

const positionLabels: Record<string, string> = {
  DOCTOR: '医生', NURSE: '护士', PHARMACIST: '药师', CASHIER: '收费员', ADMIN: '管理员'
}

const statusLabels: Record<string, string> = {
  ACTIVE: '正常', INACTIVE: '停用'
}

const list = ref<Staff[]>([])
const departments = ref<Department[]>([])
const loading = ref(false)
const searchKeyword = ref('')

const createDialog = ref(false)
const editDialog = ref(false)
const submitting = ref(false)

const form = reactive({ id: 0, name: '', gender: 'M', phone: '', deptId: 0, position: 'DOCTOR', username: '' })

function deptName(id: number): string {
  return departments.value.find(d => d.id === id)?.name || ''
}

async function fetchList() {
  loading.value = true
  try {
    const all = await orgApi.listStaff()
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase()
      list.value = all.filter((s) => s.name.toLowerCase().includes(kw) || s.phone.includes(kw))
    } else {
      list.value = all
    }
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

async function loadDepartments() {
  try { departments.value = await orgApi.listDepartments() } catch { /* ignore */ }
}

function resetForm() {
  form.id = 0; form.name = ''; form.gender = 'M'; form.phone = ''
  form.deptId = 0; form.position = 'DOCTOR'; form.username = ''
}

function openEdit(row: Staff) {
  Object.assign(form, {
    id: row.id, name: row.name, gender: row.gender,
    phone: row.phone, deptId: row.deptId,
    position: row.position, username: row.username
  })
  editDialog.value = true
}

async function submitCreate() {
  if (!form.name.trim()) { ElMessage.warning('请填写姓名'); return }
  if (!form.deptId) { ElMessage.warning('请选择科室'); return }
  submitting.value = true
  try {
    await orgApi.createStaff({
      name: form.name, gender: form.gender, phone: form.phone,
      deptId: form.deptId, position: form.position, username: form.username
    })
    ElMessage.success('员工已创建')
    createDialog.value = false
    resetForm()
    await fetchList()
  } catch { ElMessage.error('创建失败') }
  finally { submitting.value = false }
}

async function submitEdit() {
  if (!form.deptId) { ElMessage.warning('请选择科室'); return }
  submitting.value = true
  try {
    await orgApi.updateStaff(form.id, {
      name: form.name, gender: form.gender, phone: form.phone,
      deptId: form.deptId, position: form.position, username: form.username
    })
    ElMessage.success('员工信息已更新')
    editDialog.value = false
    await fetchList()
  } catch { ElMessage.error('更新失败') }
  finally { submitting.value = false }
}

async function handleDelete(row: Staff) {
  try {
    await ElMessageBox.confirm('确定要删除该员工吗？', '确认')
    await orgApi.deleteStaff(row.id)
    ElMessage.success('员工已删除')
    await fetchList()
  } catch {
    // 取消或失败均静默处理
  }
}

onMounted(() => { fetchList(); loadDepartments() })
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>员工管理</h2>
      <div>
        <el-input v-model="searchKeyword" placeholder="搜索姓名/手机号" clearable style="width:200px;margin-right:8px" @clear="fetchList" @keyup.enter="fetchList" />
        <el-button type="primary" @click="fetchList">搜索</el-button>
        <el-button type="success" @click="createDialog = true; resetForm()">+ 新建员工</el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border stripe empty-text="暂无员工">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="姓名" width="90" />
      <el-table-column label="性别" width="60"><template #default="{ row }">{{ row.gender === 'M' ? '男' : '女' }}</template></el-table-column>
      <el-table-column prop="phone" label="手机号" width="130" />
      <el-table-column label="科室" width="120"><template #default="{ row }">{{ deptName(row.deptId) }}</template></el-table-column>
      <el-table-column label="职称" width="90"><template #default="{ row }">{{ positionLabels[row.position] || row.position }}</template></el-table-column>
      <el-table-column prop="username" label="账号" width="110" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ statusLabels[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createDialog" title="新建员工" width="460px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="性别"><el-select v-model="form.gender"><el-option label="男" value="M" /><el-option label="女" value="F" /></el-select></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="职称"><el-select v-model="form.position"><el-option label="医生" value="DOCTOR" /><el-option label="护士" value="NURSE" /><el-option label="药师" value="PHARMACIST" /><el-option label="收费员" value="CASHIER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item>
        <el-form-item label="科室"><el-select v-model="form.deptId" placeholder="请选择科室" style="width:100%"><el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" /></el-select></el-form-item>
        <el-form-item label="账号"><el-input v-model="form.username" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialog" title="编辑员工" width="460px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="性别"><el-select v-model="form.gender"><el-option label="男" value="M" /><el-option label="女" value="F" /></el-select></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="职称"><el-select v-model="form.position"><el-option label="医生" value="DOCTOR" /><el-option label="护士" value="NURSE" /><el-option label="药师" value="PHARMACIST" /><el-option label="收费员" value="CASHIER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item>
        <el-form-item label="科室"><el-select v-model="form.deptId" placeholder="请选择科室" style="width:100%"><el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" /></el-select></el-form-item>
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