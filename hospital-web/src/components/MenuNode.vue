<script setup lang="ts">
import type { MenuItem } from '@/types/menu'
import { iconMap } from '@/layouts/icon-map'
import MenuNode from './MenuNode.vue'

defineProps<{ items: MenuItem[] }>()
</script>

<template>
  <template v-for="item in items" :key="item.key">
    <el-sub-menu v-if="item.children && item.children.length" :index="item.key">
      <template #title>
        <el-icon v-if="iconMap[item.icon]"><component :is="iconMap[item.icon]" /></el-icon>
        <span>{{ item.title }}</span>
      </template>
      <MenuNode :items="item.children" />
    </el-sub-menu>
    <el-menu-item v-else :index="item.path ?? item.key">
      <el-icon v-if="iconMap[item.icon]"><component :is="iconMap[item.icon]" /></el-icon>
      <span>{{ item.title }}</span>
    </el-menu-item>
  </template>
</template>
