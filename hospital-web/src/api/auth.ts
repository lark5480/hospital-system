import http from './http'

export interface LoginResponse {
  token: string
  username: string
  name: string
  position: string
  roles: string[]
  authorities: string[]
}

/** 登录:传 phone + password,成功返回 token + 角色 + 权限 */
export function login(phone: string, password: string) {
  return http.post<LoginResponse>('/auth/login', null, { params: { phone, password } }).then((r) => r.data)
}

/** 自己改密。 */
export function changePassword(oldPassword: string, newPassword: string) {
  return http.post('/auth/password/change', { oldPassword, newPassword }).then((r) => r.data)
}
