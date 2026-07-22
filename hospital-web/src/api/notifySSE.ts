/**
 * 通知SSE实时推送客户端。
 * 订阅后，新通知到达时自动推送，无需轮询。
 */

export interface NotificationRecord {
  id: number
  type: string
  content: string
  receivedAt: string
  targetRole: string
  targetDeptId?: number | null
}

type NotificationHandler = (record: NotificationRecord) => void

let eventSource: EventSource | null = null
let handlers: NotificationHandler[] = []

/**
 * 订阅通知推送。
 * @param handler 通知到达回调
 */
export function subscribeNotifications(handler: NotificationHandler): () => void {
  unsubscribe()

  eventSource = new EventSource('/api/notify/subscribe')
  handlers.push(handler)

  eventSource.addEventListener('notification', (e) => {
    try {
      const data = JSON.parse(e.data) as NotificationRecord
      handlers.forEach(h => h(data))
    } catch {
      // 解析失败忽略
    }
  })

  eventSource.onerror = () => {
    console.warn('[Notify SSE] 连接错误，将自动重连')
  }

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
