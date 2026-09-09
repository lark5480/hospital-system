import http from './http'

export interface LoginResponse {
  token: string
  username: string
  name: string
  position: string
  department?: string | null
  departmentId?: number | null
  roles: string[]
  authorities: string[]
  /**
   * R-10: 当前账号仍在使用系统默认口令(123456)登录。
   * 为 true 时路由守卫会强制跳转到改密页,直到改密成功。
   */
  mustChangePassword: boolean
}

/** R-10: 账号锁定(429)响应体。 */
export interface LockedResponse {
  error: string
  locked: boolean
}

/** R-10: 账号锁定的统一中文提示(与后端 AuthController.tooManyAttempts 文案保持一致)。 */
export const ACCOUNT_LOCKED_MESSAGE = '登录失败次数过多,账号已锁定,请 15 分钟后重试'

/** R-10: 新密码策略常量(与后端 PasswordController 保持一致,供前端做即时校验)。 */
export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 64

/** R-10: 常见弱口令黑名单(小写比对,与后端 PasswordController.WEAK_PASSWORDS 一致)。 */
const WEAK_PASSWORDS = new Set([
  '123456', '1234567', '12345678', '123456789', '1234567890',
  'password', 'password1', 'passw0rd', 'abc12345', 'abc123456',
  '11111111', '00000000', '88888888', 'a1234567', 'qwerty123',
  'iloveyou', 'admin123', 'administrator', 'letmein', 'welcome1'
])

/**
 * R-10: 新密码即时校验(前端镜像后端 PasswordController.validateNewPassword)。
 * 目的:让用户在提交前就看到中文原因,而不是被后端 400 拒绝。
 *
 * @returns 校验通过返回空串;否则返回中文原因
 */
export function validateNewPassword(newPassword: string, oldPassword: string): string {
  if (!newPassword) return '新密码不能为空'
  if (newPassword.length < PASSWORD_MIN_LENGTH || newPassword.length > PASSWORD_MAX_LENGTH) {
    return `新密码长度需为 ${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH} 位`
  }
  const hasLetter = /[A-Za-z]/.test(newPassword)
  const hasDigit = /\d/.test(newPassword)
  if (!hasLetter || !hasDigit) return '新密码必须同时包含字母和数字'
  if (WEAK_PASSWORDS.has(newPassword.toLowerCase())) return '新密码过于简单,属于常见弱口令,请更换'
  if (newPassword === oldPassword) return '新密码不能与旧密码相同'
  return ''
}

/**
 * 登录:R-12 起入参走 JSON 请求体(@RequestBody),不再拼 query。
 * 原写法把密码放在 URL 上,会泄漏到 access log / 浏览器历史 / Referer。
 */
export function login(phone: string, password: string) {
  return http.post<LoginResponse>('/auth/login', { phone, password }).then((r) => r.data)
}

/** 自己改密。新密码需满足 8~64 位且同时含字母和数字(后端强校验)。 */
export function changePassword(oldPassword: string, newPassword: string) {
  return http.post('/auth/password/change', { oldPassword, newPassword }).then((r) => r.data)
}
