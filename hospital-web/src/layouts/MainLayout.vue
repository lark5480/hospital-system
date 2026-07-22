<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { ArrowDown, Bell } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'
import { useTabsStore } from '@/stores/tabs'
import { useNotificationStore } from '@/stores/notification'
import MenuNode from '@/components/MenuNode.vue'
import TabBar from '@/components/TabBar.vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import UserInfoDialog from '@/components/UserInfoDialog.vue'

const passwordDialog = ref<InstanceType<typeof ChangePasswordDialog> | null>(null)
const userInfoDialog = ref<InstanceType<typeof UserInfoDialog> | null>(null)

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const menu = useMenuStore()
const tabs = useTabsStore()
const notifyStore = useNotificationStore()

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

function goToNotifications() {
  notifyStore.clearUnread()
  router.push('/notifications')
}

// 菜单级权限:后端按当前用户角色下发可见菜单树,此处仅渲染,不硬编码。
onMounted(() => {
  menu.fetchMenu()
  tabs.load()
  tabs.open(route) // 初始页也开一个标签
  // 订阅通知推送
  notifyStore.fetchEvents()
  notifyStore.setupSSE()
})

onUnmounted(() => {
  notifyStore.cleanup()
})
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <svg class="logo-mark" viewBox="0 0 24 24" width="22" height="22" fill="none"
          stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
          <path d="M12 8v8M8 12h8" />
        </svg>
        <span class="logo-text">医院信息系统</span>
      </div>
      <el-menu :default-active="activeMenu" class="menu" @select="handleSelect">
        <MenuNode :items="menu.menus" />
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <span class="title">{{ (route.meta.title as string) || '医院信息系统' }}</span>
        <div class="user-area">
          <template v-if="auth.authenticated">
            <!-- 通知铃铛 -->
            <el-badge :value="notifyStore.unreadCount" :hidden="notifyStore.unreadCount === 0" :max="99">
              <el-button :icon="Bell" circle @click="goToNotifications" />
            </el-badge>
            <el-dropdown trigger="click">
              <span class="user-dropdown-trigger">
                <span class="user-name">{{ auth.name || auth.username }}</span>
                <el-tag v-if="auth.department" size="small" type="warning" effect="plain">{{ auth.department }}</el-tag>
                <el-icon><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <div class="user-info-header">
                    <div class="ui-name">{{ auth.name || auth.username }}</div>
                    <div class="ui-meta">
                      <el-tag v-if="auth.department" size="small" type="warning">{{ auth.department }}</el-tag>
                      <el-tag v-for="r in auth.roles" :key="r" size="small" type="success">{{ r }}</el-tag>
                    </div>
                  </div>
                  <el-dropdown-item divided @click="userInfoDialog?.open()">个人信息</el-dropdown-item>
                  <el-dropdown-item @click="passwordDialog?.open()">修改密码</el-dropdown-item>
                  <el-dropdown-item divided @click="auth.logout()">退出</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <el-button v-else type="primary" size="small" @click="auth.login()">登录</el-button>
        </div>
        <ChangePasswordDialog ref="passwordDialog" />
        <UserInfoDialog ref="userInfoDialog" />
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
  background: var(--sidebar-bg);
  color: var(--sidebar-text-active);
  display: flex;
  flex-direction: column;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 0 var(--sp-4);
  font-weight: var(--fw-bold);
  font-size: var(--fs-md);
  color: var(--sidebar-text-active);
  border-bottom: 1px solid var(--sidebar-border);
}
.logo-mark { color: var(--brand-400); flex: 0 0 auto; }
.logo-text { letter-spacing: 0.5px; }
.menu {
  border-right: none;
  background: var(--sidebar-bg);
  flex: 1;
  overflow-y: auto;
  --el-menu-bg-color: var(--sidebar-bg);
  --el-menu-text-color: var(--sidebar-text);
  --el-menu-hover-bg-color: var(--sidebar-hover-bg);
  --el-menu-active-color: var(--sidebar-text-active);
}
.menu :deep(.el-menu-item) {
  color: var(--sidebar-text);
}
.menu :deep(.el-menu-item:hover) {
  background: var(--sidebar-hover-bg);
  color: var(--sidebar-text-active);
}
.menu :deep(.el-menu-item.is-active) {
  color: var(--sidebar-text-active);
  background: var(--sidebar-active-bg);
}
.menu :deep(.el-sub-menu__title) {
  color: var(--sidebar-text);
}
.menu :deep(.el-sub-menu__title:hover) {
  background: var(--sidebar-hover-bg);
  color: var(--sidebar-text-active);
}
.menu :deep(.el-sub-menu.is-active .el-sub-menu__title) {
  color: var(--sidebar-text-active);
}
/* 子菜单展开区域背景 */
.menu :deep(.el-sub-menu .el-menu) {
  background: var(--sidebar-bg-sub);
}
/* 子菜单项 hover */
.menu :deep(.el-sub-menu .el-menu-item:hover) {
  background: var(--sidebar-hover-bg);
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
}
.title { font-weight: var(--fw-semibold); font-size: var(--fs-md); color: var(--text-primary); }
.user-area { display: flex; align-items: center; gap: var(--sp-2); }
.user-dropdown-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  cursor: pointer;
  color: var(--text-regular);
  outline: none;
}
.user-name { font-weight: var(--fw-semibold); font-size: var(--fs-base); }
/* 下拉展开后的用户信息头(只读展示姓名/科室/角色) */
.user-info-header {
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--border-light);
}
.ui-name { font-weight: var(--fw-semibold); font-size: var(--fs-base); color: var(--text-primary); margin-bottom: var(--sp-2); }
.ui-meta { display: flex; flex-wrap: wrap; gap: var(--sp-1); }
.main { background: var(--bg-page); padding: var(--sp-6); }
</style>
