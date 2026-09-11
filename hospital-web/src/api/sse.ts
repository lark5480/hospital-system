import http from './http'

/**
 * R-34: 获取短期 SSE 订阅凭证(ticket)。
 *
 * 浏览器原生 EventSource 无法自定义请求头,SSE 订阅只能把凭证放在 URL 上。
 * 直接放长期 JWT 会泄漏到浏览器历史 / 网关日志 / Referer,因此改为:
 * 先用本接口(走既有 http 实例,自动带 Bearer)换取一个 60 秒有效、
 * 且仅能用于 SSE 订阅的 ticket,再用 `?ticket=<ticket>` 建立 EventSource。
 *
 * @returns 短期 ticket 字符串
 * @throws Error 换取失败(网络错误 / 未认证等)时抛出可读错误
 */
export async function fetchSseTicket(): Promise<string> {
  try {
    const { data } = await http.post<{ ticket: string; expiresIn: number }>('/core/sse/ticket')
    if (!data?.ticket) {
      throw new Error('empty-ticket')
    }
    return data.ticket
  } catch (e) {
    // R-11: 把 HTTP 状态透出来 —— 调用方需要区分"认证/授权失败(重试无意义)"与"网络抖动(值得重试)"。
    // 吞掉状态会让 SSE 客户端对着 401/403 无限退避重连。
    const status = (e as { response?: { status?: number } })?.response?.status
    const err = new Error('获取实时推送凭证失败，请稍后重试') as Error & { status?: number }
    err.status = status
    throw err
  }
}
