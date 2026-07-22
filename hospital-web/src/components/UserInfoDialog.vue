<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { AUTHORITY_OPTIONS } from '@/types/iam'

const auth = useAuthStore()
const visible = ref(false)

/** 权限代码 → 中文标签(找不到则回退原值)。 */
const authorityLabels = computed(() => {
  const map = new Map(AUTHORITY_OPTIONS.map((a) => [a.value, a.label]))
  return auth.authorities.map((a) => map.get(a) ?? a)
})

function open() {
  visible.value = true
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="个人信息" width="420px" :close-on-click-modal="true">
    <el-descriptions :column="1" border>
      <el-descriptions-item label="手机号">{{ auth.username || '-' }}</el-descriptions-item>
      <el-descriptions-item label="姓名">{{ auth.name || '-' }}</el-descriptions-item>
      <el-descriptions-item label="所属科室">
        <el-tag v-if="auth.department" size="small" type="warning">{{ auth.department }}</el-tag>
        <span v-else>-</span>
      </el-descriptions-item>
      <el-descriptions-item label="角色">
        <el-tag v-for="role in auth.roles" :key="role" size="small" type="success" style="margin-right: 4px">
          {{ role }}
        </el-tag>
        <span v-if="!auth.roles?.length">-</span>
      </el-descriptions-item>
      <el-descriptions-item label="权限">
        <div style="display: flex; flex-wrap: wrap; gap: 4px">
          <el-tag v-for="(label, i) in authorityLabels" :key="i" size="small">
            {{ label }}
          </el-tag>
          <span v-if="!authorityLabels.length">-</span>
        </div>
      </el-descriptions-item>
      <el-descriptions-item label="权限数">{{ auth.authorities?.length || 0 }} 项</el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>
