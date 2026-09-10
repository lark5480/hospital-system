import { describe, it, expect } from 'vitest'
import { validateNewPassword, PASSWORD_MIN_LENGTH } from '@/api/auth'

// 纯函数 util:新密码即时校验(前端镜像后端策略)。
// 覆盖空值、长度、字符组成、弱口令、与旧密码相同、合规通过六类边界。
describe('validateNewPassword (R-10 密码策略)', () => {
  const OLD = 'OldPass123'

  it('空密码被拒绝', () => {
    expect(validateNewPassword('', OLD)).toBe('新密码不能为空')
  })

  it('长度不足被拒绝(提示含最短长度)', () => {
    const msg = validateNewPassword('a1', OLD)
    expect(msg).not.toBe('')
    expect(msg).toContain(String(PASSWORD_MIN_LENGTH))
  })

  it('仅字母或仅数字被拒绝', () => {
    expect(validateNewPassword('abcdefgh', OLD)).toBe('新密码必须同时包含字母和数字')
    expect(validateNewPassword('12345678', OLD)).toBe('新密码必须同时包含字母和数字')
  })

  it('常见弱口令被拒绝', () => {
    expect(validateNewPassword('abc12345', OLD)).not.toBe('')
  })

  it('与旧密码相同被拒绝', () => {
    expect(validateNewPassword(OLD, OLD)).toBe('新密码不能与旧密码相同')
  })

  it('合规新密码通过(返回空串)', () => {
    expect(validateNewPassword('GoodPass123', OLD)).toBe('')
  })
})
