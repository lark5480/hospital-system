<template>
  <div v-if="error" class="error-boundary">
    <el-result icon="error" title="页面出错了" :sub-title="errorMessage">
      <template #extra>
        <el-button type="primary" @click="reload">重新加载</el-button>
      </template>
    </el-result>
  </div>
  <slot v-else />
</template>

<script setup lang="ts">
import { ref, onErrorCaptured } from 'vue'

const error = ref<Error | null>(null)
const errorMessage = ref('')

onErrorCaptured((err) => {
  error.value = err as Error
  errorMessage.value = (err as Error).message || '未知错误'
  console.error('[ErrorBoundary]', err)
  return false
})

function reload() {
  error.value = null
  errorMessage.value = ''
  window.location.reload()
}
</script>

<style scoped>
.error-boundary {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 400px;
}
</style>
