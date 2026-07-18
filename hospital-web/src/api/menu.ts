import http from './http'
import type { MenuItem } from '@/types/menu'

/** 拉取当前用户可见的导航菜单树(后端按权限裁剪)。 */
export function getMenu(): Promise<MenuItem[]> {
  return http.get<MenuItem[]>('/core/iam/menu').then((r) => r.data)
}

// 菜单管理API
export function fetchMenuTree() {
  return http.get('/core/iam/menu-manage/tree').then(r => r.data)
}

export function createMenu(menu: any) {
  return http.post('/core/iam/menu-manage', menu).then(r => r.data)
}

export function updateMenu(id: number, menu: any) {
  return http.put(`/core/iam/menu-manage/${id}`, menu).then(r => r.data)
}

export function deleteMenu(id: number) {
  return http.delete(`/core/iam/menu-manage/${id}`)
}
