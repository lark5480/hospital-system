/**
 * 排队看板SSE实时推送客户端。
 * 替代轮询，服务器有数据变更时自动推送。
 */

import { useAuthStore } from '@/stores/auth'

type BoardUpdateHandler = (event: { station: string; taskId: number; action: string }) => void

let eventSource: EventSource | null = null
let handlers: BoardUpdateHandler[] = []

/**
 * 订阅看板数据变更推送。
 * @param handler 数据变更回调
 * @param station 可选，按工位过滤
 */
export function subscribeBoardUpdates(handler: BoardUpdateHandler, station?: string): () => void {
  // 先清理旧连接
  unsubscribe()

  // 浏览器原生 EventSource 无法自定义请求头,JWT 只能通过 query 参数 token 携带
  const params = new URLSearchParams()
  if (station) params.set('station', station)
  const token = useAuthStore().token
  if (token) params.set('token', token)
  const qs = params.toString()
  const url = `/api/core/dispatch/sse/subscribe${qs ? `?${qs}` : ''}`

  eventSource = new EventSource(url)
  handlers.push(handler)

  eventSource.addEventListener('board-update', (e) => {
    try {
      const data = JSON.parse(e.data)
      handlers.forEach(h => h(data))
    } catch {
      // 解析失败忽略
    }
  })

  eventSource.onerror = () => {
    // EventSource会自动重连，无需手动处理
    console.warn('[SSE] 连接错误，将自动重连')
  }

  // 返回取消订阅函数
  return () => {
    handlers = handlers.filter(h => h !== handler)
    if (handlers.length === 0) {
      unsubscribe()
    }
  }
}

/**
 * 取消所有订阅，关闭连接。
 */
export function unsubscribe(): void {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  handlers = []
}
