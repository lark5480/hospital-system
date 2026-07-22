<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Close, Refresh, CircleClose, Remove } from '@element-plus/icons-vue'
import { useTabsStore } from '@/stores/tabs'

const router = useRouter()
const tabs = useTabsStore()

function go(path: string) {
  if (path !== tabs.active) {
    tabs.activate(path)
    router.push(path)
  }
}

function onClose(path: string, e: MouseEvent) {
  e.stopPropagation()
  const target = tabs.close(path)
  if (target) router.push(target)
}

function onRefresh() {
  if (tabs.active) tabs.reload(tabs.active)
}

function onCloseOthers() {
  tabs.closeOthers(tabs.active)
}

function onCloseAll() {
  const target = tabs.closeAll()
  if (target) router.push(target)
}
</script>

<template>
  <div class="tab-bar">
    <div class="tabs-scroll">
      <div
        v-for="t in tabs.tabs"
        :key="t.path"
        class="tab"
        :class="{ active: t.path === tabs.active }"
        :title="t.title"
        @click="go(t.path)"
        @auxclick="(e) => e.button === 1 && onClose(t.path, e as MouseEvent)"
      >
        <span class="tab-title">{{ t.title }}</span>
        <el-icon class="tab-close" @click="onClose(t.path, $event)"><Close /></el-icon>
      </div>
    </div>

    <el-dropdown class="tab-actions" trigger="click">
      <el-button text size="small" class="more-btn" :icon="CircleClose" />
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item @click="onRefresh">
            <el-icon><Refresh /></el-icon><span class="dd-text">刷新当前</span>
          </el-dropdown-item>
          <el-dropdown-item @click="onCloseOthers">
            <el-icon><Remove /></el-icon><span class="dd-text">关闭其他</span>
          </el-dropdown-item>
          <el-dropdown-item @click="onCloseAll">
            <el-icon><CircleClose /></el-icon><span class="dd-text">关闭全部</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.tab-bar {
  display: flex;
  align-items: stretch;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
  height: 40px;
  padding: 0 var(--sp-1) 0 var(--sp-2);
}
.tabs-scroll {
  flex: 1;
  display: flex;
  align-items: stretch;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: thin;
}
.tabs-scroll::-webkit-scrollbar {
  height: 4px;
}
.tabs-scroll::-webkit-scrollbar-thumb {
  background: var(--slate-300);
  border-radius: var(--radius-sm);
}
.tab {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 0 var(--sp-2) 0 var(--sp-4);
  height: 40px;
  border-right: 1px solid var(--border-light);
  color: var(--text-secondary);
  font-size: var(--fs-sm);
  cursor: pointer;
  white-space: nowrap;
  position: relative;
  user-select: none;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.tab:hover {
  background: var(--bg-muted);
}
.tab.active {
  color: var(--brand);
  background: var(--brand-subtle);
}
.tab.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--brand);
}
.tab-title {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tab-close {
  font-size: var(--fs-xs);
  border-radius: var(--radius-full);
  padding: 2px;
  opacity: 0.45;
  transition: opacity var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.tab-close:hover {
  opacity: 1;
  background: var(--slate-200);
}
.tab-actions {
  display: flex;
  align-items: center;
  padding-left: var(--sp-2);
  border-left: 1px solid var(--border-light);
}
.more-btn {
  color: var(--text-secondary);
}
.dd-text {
  margin-left: var(--sp-2);
}
</style>
