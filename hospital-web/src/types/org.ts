export interface Department {
  id: number
  name: string
  code: string
  description: string
  createdAt: string
}

export interface Staff {
  id: number
  name: string
  gender: string
  phone: string
  deptId: number
  position: string
  username: string
  status: string
  createdAt: string
  /** 关联的统一登录账号 ID(platform.sys_user.id);未开通账号的员工为 null,重置密码入口据此禁用 */
  userId?: number
}

export interface DepartmentForm {
  name: string
  code: string
  description: string
}

export interface StaffForm {
  name: string
  gender: string
  phone: string
  deptId: number
  position: string
  username: string
}
