<template>
  <div class="menu-manage">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>菜单管理</span>
          <el-button type="primary" @click="handleAdd">新增菜单</el-button>
        </div>
      </template>
      
      <el-table :data="menuTree" row-key="id" default-expand-all>
        <el-table-column prop="title" label="菜单名称" />
        <el-table-column prop="key" label="标识" />
        <el-table-column prop="path" label="路由路径" />
        <el-table-column prop="icon" label="图标" />
        <el-table-column label="权限">
          <template #default="{ row }">
            <el-tag v-for="auth in row.authorities" :key="auth" size="small">
              {{ auth }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑菜单' : '新增菜单'">
      <el-form :model="form" label-width="100px">
        <el-form-item label="菜单名称">
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="标识">
          <el-input v-model="form.key" />
        </el-form-item>
        <el-form-item label="路由路径">
          <el-input v-model="form.path" placeholder="分组节点留空" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" />
        </el-form-item>
        <el-form-item label="权限">
          <el-select v-model="form.authorities" multiple>
            <el-option v-for="auth in AUTHORITY_OPTIONS" :key="auth.value" :label="auth.label" :value="auth.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchMenuTree, createMenu, updateMenu, deleteMenu } from '@/api/menu'
import { AUTHORITY_OPTIONS } from '@/types/iam'

const menuTree = ref([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref({
  id: null,
  title: '',
  key: '',
  path: '',
  icon: '',
  authorities: []
})

onMounted(() => {
  loadMenus()
})

async function loadMenus() {
  menuTree.value = await fetchMenuTree()
}

function handleAdd() {
  isEdit.value = false
  form.value = { id: null, title: '', key: '', path: '', icon: '', authorities: [] }
  dialogVisible.value = true
}

function handleEdit(row: any) {
  isEdit.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

async function handleSave() {
  if (isEdit.value) {
    // 编辑态 id 必然存在;为空说明表单数据异常,直接放弃提交而不是打到 /undefined
    if (form.value.id == null) {
      ElMessage.error('菜单数据异常,缺少 id,请重新打开编辑')
      return
    }
    await updateMenu(form.value.id, form.value)
  } else {
    await createMenu(form.value)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  loadMenus()
}

async function handleDelete(row: any) {
  await ElMessageBox.confirm('确定删除该菜单?', '提示', { type: 'warning' })
  await deleteMenu(row.id)
  ElMessage.success('删除成功')
  loadMenus()
}
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
