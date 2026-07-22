import http from './http'

export interface AuditLogItem {
  id: number
  actor: string
  action: string
  target: string
  detail: string | null
  createdAt: string
}

export interface AuditLogPage {
  items: AuditLogItem[]
  total: number
  pageNum: number
  pageSize: number
}

export function fetchAuditLogs(params: {
  action?: string
  actor?: string
  pageNum?: number
  pageSize?: number
}) {
  return http.get<AuditLogPage>('/core/audit-logs', { params }).then(r => r.data)
}
