import http from './http'
import type { RoleWithAuthorities } from '@/types/iam'

/** 查询全部角色 + 各自权限。 */
export function listRoles(): Promise<RoleWithAuthorities[]> {
  return http.get<RoleWithAuthorities[]>('/core/iam/roles').then((r) => r.data)
}

/** 保存某角色的权限(全量覆盖)。 */
export function saveRoleAuthorities(code: string, authorities: string[]): Promise<{ updated: string; authorities: string[] }> {
  return http.put(`/core/iam/roles/${code}/authorities`, authorities).then((r) => r.data)
}
