import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMenu } from '@/api/menu'
import type { MenuItem } from '@/types/menu'

/**
 * 导航菜单状态(Pinia store)。
 * 菜单由后端按当前用户权限下发(菜单级权限的唯一事实源),前端只负责渲染,
 * 不再硬编码任何菜单项——改菜单只改后端 MenuConfig。
 */
export const useMenuStore = defineStore('menu', () => {
  const menus = ref<MenuItem[]>([])
  const loaded = ref(false)

  async function fetchMenu() {
    try {
      menus.value = await getMenu()
    } catch (e) {
      console.warn('[menu] 拉取导航菜单失败,使用空菜单', e)
      menus.value = []
    } finally {
      loaded.value = true
    }
  }

  return { menus, loaded, fetchMenu }
})
