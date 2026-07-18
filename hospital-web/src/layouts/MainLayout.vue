<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'
import { useTabsStore } from '@/stores/tabs'
import MenuNode from '@/components/MenuNode.vue'
import TabBar from '@/components/TabBar.vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'

const passwordDialog = ref<InstanceType<typeof ChangePasswordDialog> | null>(null)

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const menu = useMenuStore()
const tabs = useTabsStore()

// 路由驱动开标签:任何导航(含详情页)自动新增/聚焦一个标签,互不覆盖
router.afterEach((to) => {
  tabs.open(to)
})

const activeMenu = computed(() => {
  // 详情页高亮归属到「门诊就诊」
  if (route.path.startsWith('/visits/')) return '/visits'
  return route.path
})


function handleSelect(index: string) {
  router.push(index)
}

// 菜单级权限:后端按当前用户角色下发可见菜单树,此处仅渲染,不硬编码。
onMounted(() => {
  menu.fetchMenu()
  tabs.load()
  tabs.open(route) // 初始页也开一个标签
})
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">🏥 医院信息系统</div>
      <el-menu :default-active="activeMenu" class="menu" @select="handleSelect">
        <MenuNode :items="menu.menus" />
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <span class="title">{{ (route.meta.title as string) || '医院信息系统' }}</span>
        <div class="user-area">
          <template v-if="auth.authenticated">
            <el-dropdown trigger="click">
              <span class="user-dropdown-trigger">
                {{ auth.name || auth.username }}
                <el-icon><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="passwordDialog?.open()">修改密码</el-dropdown-item>
                  <el-dropdown-item divided @click="auth.logout()">退出</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <el-button v-else type="primary" size="small" @click="auth.login()">登录</el-button>
        </div>
        <ChangePasswordDialog ref="passwordDialog" />
      </el-header>

      <TabBar />

      <el-main class="main">
        <RouterView v-slot="{ Component }">
          <KeepAlive :max="15">
            <component
              :is="Component"
              :key="route.path + ':' + (tabs.reloadTick[route.path] ?? 0)"
            />
          </KeepAlive>
        </RouterView>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout { height: 100vh; }
.aside {
  background: #1f2329;
  color: #fff;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 18px;
  font-weight: 700;
  font-size: 15px;
  border-bottom: 1px solid #2c313a;
}
.menu {
  border-right: none;
  background: #1f2329;
  flex: 1;
  overflow-y: auto;
}
.menu :deep(.el-menu-item) {
  color: #c5c9d0;
}
.menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: #2c6bed;
}
.menu :deep(.el-sub-menu__title) {
  color: #c5c9d0;
}
.menu :deep(.el-sub-menu__title:hover) {
  background: #2c313a;
}
.menu :deep(.el-sub-menu.is-active .el-sub-menu__title) {
  color: #fff;
}
/* 子菜单展开区域背景 */
.menu :deep(.el-sub-menu .el-menu) {
  background: #1f2329;
}
/* 子菜单项 hover */
.menu :deep(.el-sub-menu .el-menu-item:hover) {
  background: #2c313a;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e5e5e7;
}
.title { font-weight: 600; font-size: 15px; }
.user-area { display: flex; align-items: center; gap: 8px; }
.user-dropdown-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  font-weight: 600;
  font-size: 14px;
  color: #303133;
  outline: none;
}
.main { background: #f5f5f7; padding: 24px; }
</style>
