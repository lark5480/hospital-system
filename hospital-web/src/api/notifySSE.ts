/**
 * 通知SSE实时推送客户端。
 * 订阅后，新通知到达时自动推送，无需轮询。
 *
 * R-11: 通知服务的 SSE 端点已收紧为"短期 ticket（scope=sse）+ 员工权限"，匿名/患者不可订阅。
 * 因此本客户端改为与 dispatchSSE.ts 同款流程：先取 60 秒有效的 ticket，再带上 `?ticket=` 建连。
 */

import { fetchSseTicket } from './sse'

export interface NotificationRecord {
  id: number
  type: string
  content: string
  receivedAt: string
  targetRole: string
  targetDeptId?: number | null
}

type NotificationHandler = (record: NotificationRecord) => void

/**
 * R-11: 重连续连退避参数。
 * ticket 只有 60 秒有效,而 EventSource 的原生自动重连会一直复用同一个(已过期的)ticket,
 * 必然失败 → 因此改为「主动重连」:关闭旧连接 → 重新取 ticket → 新建 EventSource。
 * 退避从 5s 起、每次翻倍、上限 30s,避免服务端异常时疯狂重试。
 */
const RECONNECT_BASE_MS = 5000
const RECONNECT_MAX_MS = 30000

let eventSource: EventSource | null = null
let handlers: NotificationHandler[] = []
/** R-11: 重连定时器（unsubscribe/dispose 时必须清除,避免定时器泄漏） */
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
/** R-11: 当前退避时长 */
let reconnectDelay = RECONNECT_BASE_MS
/** R-11: 是否已主动取消订阅;取 ticket 是异步的,需用它防止"取消后仍建立连接" */
let disposed = false

/**
 * 订阅通知推送。
 * @param handler 通知到达回调
 * @returns 取消本次订阅的函数（对外签名保持同步不变）
 */
export function subscribeNotifications(handler: NotificationHandler): () => void {
  // 先清理旧连接(会置 disposed=true)
  unsubscribe()

  // 重置订阅上下文
  disposed = false
  reconnectDelay = RECONNECT_BASE_MS
  handlers.push(handler)

  // R-11: 内部改为异步取 ticket 后建连（对外仍是同步返回取消函数）
  void connect()

  return () => {
    handlers = handlers.filter(h => h !== handler)
    if (handlers.length === 0) {
      unsubscribe()
    }
  }
}

/**
 * R-11: 取 ticket → 新建 EventSource。失败时走退避重连。
 */
async function connect(): Promise<void> {
  // 关闭已有连接,避免与原生自动重连叠加
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }

  let ticket: string
  try {
    ticket = await fetchSseTicket()
  } catch (e) {
    // R-11: 认证/授权失败(401/403)是终态 —— 重试不会变好,只会变成对 401 的死循环
    // (通知为全院级内容,后端仅对员工开放;非员工账号本就不该走到这里,见 stores/notification.ts 的门禁)。
    // 仅对网络类失败做退避重连。
    const status = (e as { status?: number })?.status
    if (status === 401 || status === 403) {
      console.warn('[Notify SSE] 无权订阅通知推送(HTTP ' + status + ')，已停止重试')
      return
    }
    if (!disposed) {
      console.warn('[Notify SSE] 获取订阅凭证失败，稍后重试', e)
      scheduleReconnect()
    }
    return
  }

  // 取票期间可能已被取消订阅,此时不得再建立连接
  if (disposed) return

  const es = new EventSource('/api/notify/subscribe?ticket=' + encodeURIComponent(ticket))
  eventSource = es

  es.addEventListener('notification', (e) => {
    try {
      const data = JSON.parse((e as MessageEvent).data) as NotificationRecord
      handlers.forEach(h => h(data))
    } catch {
      // 解析失败忽略
    }
  })

  // 连接建立成功后重置退避
  es.onopen = () => {
    reconnectDelay = RECONNECT_BASE_MS
  }

  // R-11: 不依赖 EventSource 原生自动重连(ticket 会过期),错误时主动重连
  es.onerror = () => {
    if (disposed) return
    console.warn('[Notify SSE] 连接中断(或 ticket 过期)，将在退避后重新取票连接')
    scheduleReconnect()
  }
}

/**
 * R-11: 安排一次退避重连。同一时刻只保留一个定时器,重复触发会被忽略。
 */
function scheduleReconnect(): void {
  if (disposed || reconnectTimer !== null) return

  // 关闭旧连接:它会不停用过期 ticket 自动重连,徒增无效请求
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }

  const delay = reconnectDelay
  reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS)

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    if (!disposed) void connect()
  }, delay)
}

/**
 * 取消所有订阅，关闭连接，并清理重连定时器。
 */
export function unsubscribe(): void {
  disposed = true
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  handlers = []
}
