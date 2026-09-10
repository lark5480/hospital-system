/**
 * 排队看板SSE实时推送客户端。
 * 替代轮询，服务器有数据变更时自动推送。
 */

import { fetchSseTicket } from './sse'

type BoardUpdateHandler = (event: { station: string; taskId: number; action: string }) => void

/**
 * R-34: 重连续连退避参数。
 * ticket 只有 60 秒有效,而 EventSource 的原生自动重连会一直复用同一个(已过期的)ticket,
 * 必然失败 → 因此改为「主动重连」:关闭旧连接 → 重新取 ticket → 新建 EventSource。
 * 退避从 5s 起、每次翻倍、上限 30s,避免服务端异常时疯狂重试。
 */
const RECONNECT_BASE_MS = 5000
const RECONNECT_MAX_MS = 30000

let eventSource: EventSource | null = null
let handlers: BoardUpdateHandler[] = []
/** 当前订阅的工位(重连时复用) */
let activeStation: string | undefined
/** 重连定时器 */
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
/** 当前退避时长 */
let reconnectDelay = RECONNECT_BASE_MS
/** 是否已主动取消订阅;取 ticket 是异步的,需用它防止"取消后仍建立连接" */
let disposed = false

/**
 * 订阅看板数据变更推送。
 * @param handler 数据变更回调
 * @param station 可选，按工位过滤
 * @returns 取消本次订阅的函数(对外签名保持同步不变)
 */
export function subscribeBoardUpdates(handler: BoardUpdateHandler, station?: string): () => void {
  // 先清理旧连接(会置 disposed=true)
  unsubscribe()

  // 重置订阅上下文
  disposed = false
  activeStation = station
  reconnectDelay = RECONNECT_BASE_MS
  handlers.push(handler)

  // R-34: 内部改为异步取 ticket 后建连(对外仍是同步返回取消函数)
  void connect()

  // 返回取消订阅函数
  return () => {
    handlers = handlers.filter(h => h !== handler)
    if (handlers.length === 0) {
      unsubscribe()
    }
  }
}

/**
 * R-34: 取 ticket → 新建 EventSource。失败时走退避重连。
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
    // 取票失败(网络/未登录等)也走退避重连
    if (!disposed) {
      console.warn('[SSE] 获取订阅凭证失败，稍后重试', e)
      scheduleReconnect()
    }
    return
  }

  // 取票期间可能已被取消订阅,此时不得再建立连接
  if (disposed) return

  const params = new URLSearchParams()
  if (activeStation) params.set('station', activeStation)
  // R-34: 只带短期 ticket,不再把长期 JWT 放进 URL
  params.set('ticket', ticket)

  const es = new EventSource(`/api/core/dispatch/sse/subscribe?${params.toString()}`)
  eventSource = es

  es.addEventListener('board-update', (e) => {
    try {
      const data = JSON.parse((e as MessageEvent).data)
      handlers.forEach(h => h(data))
    } catch {
      // 解析失败忽略
    }
  })

  // 连接建立成功后重置退避
  es.onopen = () => {
    reconnectDelay = RECONNECT_BASE_MS
  }

  // R-34: 不依赖 EventSource 原生自动重连(ticket 会过期),错误时主动重连
  es.onerror = () => {
    if (disposed) return
    console.warn('[SSE] 连接中断(或 ticket 过期)，将在退避后重新取票连接')
    scheduleReconnect()
  }
}

/**
 * R-34: 安排一次退避重连。同一时刻只保留一个定时器,重复触发会被忽略。
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
 * 取消所有订阅，关闭连接。
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
