<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as orgApi from '@/api/org'
import type { Department } from '@/types/org'

const list = ref<Department[]>([])
const loading = ref(false)
const searchKeyword = ref('')

const createDialog = ref(false)
const editDialog = ref(false)
const submitting = ref(false)

const form = reactive({ id: 0, name: '', code: '', description: '' })

async function fetchList() {
  loading.value = true
  try {
    const all = await orgApi.listDepartments()
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase()
      list.value = all.filter((d) => d.name.toLowerCase().includes(kw))
    } else {
      list.value = all
    }
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function resetForm() {
  form.id = 0; form.name = ''; form.code = ''; form.description = ''
}

function openEdit(row: Department) {
  Object.assign(form, {
    id: row.id, name: row.name, code: row.code, description: row.description
  })
  editDialog.value = true
}

async function submitCreate() {
  if (!form.name.trim()) { ElMessage.warning('请填写科室名称'); return }
  submitting.value = true
  try {
    await orgApi.createDepartment({ name: form.name, code: form.code, description: form.description })
    ElMessage.success('科室已创建')
    createDialog.value = false
    resetForm()
    await fetchList()
  } catch { ElMessage.error('创建失败') }
  finally { submitting.value = false }
}

async function submitEdit() {
  submitting.value = true
  try {
    await orgApi.updateDepartment(form.id, {
      name: form.name, code: form.code, description: form.description
    })
    ElMessage.success('科室信息已更新')
    editDialog.value = false
    await fetchList()
  } catch { ElMessage.error('更新失败') }
  finally { submitting.value = false }
}

async function handleDelete(row: Department) {
  try {
    await ElMessageBox.confirm('确定要删除该科室吗？', '确认')
    await orgApi.deleteDepartment(row.id)
    ElMessage.success('科室已删除')
    await fetchList()
  } catch {
    // 取消或失败均静默处理
  }
}

onMounted(fetchList)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>科室管理</h2>
      <div>
        <el-input v-model="searchKeyword" placeholder="搜索科室名称" clearable style="width:200px;margin-right:8px" @clear="fetchList" @keyup.enter="fetchList" />
        <el-button type="primary" @click="fetchList">搜索</el-button>
        <el-button type="success" @click="createDialog = true; resetForm()">+ 新建科室</el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border stripe empty-text="暂无科室">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="科室名称" width="150" />
      <el-table-column prop="code" label="科室编码" width="120" />
      <el-table-column prop="description" label="描述" min-width="200" />
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createDialog" title="新建科室" width="460px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="科室名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="科室编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialog" title="编辑科室" width="460px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="科室名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="科室编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
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
