import http from './http'
import type { MenuItem } from '@/types/menu'

/** 拉取当前用户可见的导航菜单树(后端按权限裁剪)。 */
export function getMenu(): Promise<MenuItem[]> {
  return http.get<MenuItem[]>('/core/iam/menu').then((r) => r.data)
}
