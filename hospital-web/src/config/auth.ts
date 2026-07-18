// 认证模式配置(AUTH_ENABLED 控制是否启用真登录):
//  - false(默认): 本地开发态,点"登录"按钮以模拟角色登录(可切换角色);
//  - true: 生产态,输入用户名登录,取真实角色与权限。
export const AUTH_ENABLED = (import.meta.env.VITE_AUTH_ENABLED ?? 'false') === 'true'
