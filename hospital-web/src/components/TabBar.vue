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
  background: #fff;
  border-bottom: 1px solid #e5e5e7;
  height: 40px;
  padding: 0 4px 0 8px;
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
  background: #d6d7db;
  border-radius: 2px;
}
.tab {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px 0 14px;
  height: 40px;
  border-right: 1px solid #f0f0f2;
  color: #5b5f66;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  position: relative;
  user-select: none;
  transition: background 0.15s ease, color 0.15s ease;
}
.tab:hover {
  background: #f5f6f8;
}
.tab.active {
  color: #2c6bed;
  background: #f5f5f7;
}
.tab.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: #2c6bed;
}
.tab-title {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tab-close {
  font-size: 12px;
  border-radius: 50%;
  padding: 2px;
  opacity: 0.45;
  transition: opacity 0.15s ease, background 0.15s ease;
}
.tab-close:hover {
  opacity: 1;
  background: #e3e4e8;
}
.tab-actions {
  display: flex;
  align-items: center;
  padding-left: 6px;
  border-left: 1px solid #f0f0f2;
}
.more-btn {
  color: #5b5f66;
}
.dd-text {
  margin-left: 6px;
}
</style>
