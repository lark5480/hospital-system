/** 角色 + 权限(与后端 RoleWithAuthorities 对应)。 */
export interface RoleWithAuthorities {
  code: string
  name: string
  description: string
  authorities: string[]
}

/** 可选 authority 定义(用于前端渲染勾选)。 */
export interface AuthorityOption {
  value: string
  label: string
}

/** 全部可选 authority 列表。 */
export const AUTHORITY_OPTIONS: AuthorityOption[] = [
  { value: 'visit:entry', label: '就诊录入' },
  { value: 'visit:audit', label: '就诊审核' },
  { value: 'order:execute', label: '检查执行' },
  { value: 'pharmacy:dispense', label: '处方发药' },
  { value: 'charge:pay', label: '收费结算' },
  { value: 'system:admin', label: '系统管理' },
  { value: 'patient:booking', label: '体检预约' },
]
