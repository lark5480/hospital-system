<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as iamApi from '@/api/iam'
import { AUTHORITY_OPTIONS, type RoleWithAuthorities } from '@/types/iam'

const roles = ref<RoleWithAuthorities[]>([])
const currentCode = ref('')
const selected = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)

const currentRole = computed(() => roles.value.find((r) => r.code === currentCode.value))

async function fetchRoles() {
  loading.value = true
  try {
    roles.value = await iamApi.listRoles()
    if (roles.value.length && !currentCode.value) {
      currentCode.value = roles.value[0].code
      selected.value = [...roles.value[0].authorities]
    }
  } catch {
    ElMessage.error('加载角色失败')
  } finally {
    loading.value = false
  }
}

function selectRole(code: string) {
  currentCode.value = code
  const role = roles.value.find((r) => r.code === code)
  selected.value = role ? [...role.authorities] : []
}

async function handleSave() {
  if (!currentCode.value) return
  saving.value = true
  try {
    await iamApi.saveRoleAuthorities(currentCode.value, selected.value)
    ElMessage.success('权限已保存')
    await fetchRoles()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(fetchRoles)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>角色权限管理</h2>
    </div>

    <el-row :gutter="16">
      <!-- 左侧:角色列表 -->
      <el-col :span="6">
        <el-card shadow="never">
          <template #header><span>角色</span></template>
          <el-menu :default-active="currentCode" @select="selectRole">
            <el-menu-item v-for="role in roles" :key="role.code" :index="role.code">
              {{ role.name }}
            </el-menu-item>
          </el-menu>
        </el-card>
      </el-col>

      <!-- 右侧:权限勾选 -->
      <el-col :span="18">
        <el-card shadow="never">
          <template #header>
            <span v-if="currentRole">{{ currentRole.name }} — 权限配置</span>
          </template>

          <div v-if="currentRole">
            <p class="desc">{{ currentRole.description }}</p>
            <el-checkbox-group v-model="selected">
              <el-checkbox v-for="opt in AUTHORITY_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </el-checkbox>
            </el-checkbox-group>

            <div class="footer">
              <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
            </div>
          </div>
          <el-empty v-else description="请选择左侧角色" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; margin-bottom: var(--sp-4); }
.toolbar h2 { margin: 0; font-size: var(--fs-xl); }
.desc { color: var(--text-secondary); margin-bottom: var(--sp-4); }
.el-checkbox-group { display: flex; flex-direction: column; gap: var(--sp-2); }
.footer { margin-top: var(--sp-6); text-align: right; }
</style>
