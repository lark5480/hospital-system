import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as notifyApi from '@/api/notification'
import { subscribeNotifications, unsubscribe } from '@/api/notifySSE'
import type { NotificationRecord } from '@/types/notification'
import { useAuthStore } from '@/stores/auth'

/**
 * R-11: 通知台可用的员工权限(持有任一即可),与服务端
 * `hospital-notification-service` 的 EMPLOYEE_AUTHORITIES 保持一致。
 */
const EMPLOYEE_AUTHORITIES = [
  'visit:entry', 'visit:audit', 'order:execute',
  'pharmacy:dispense', 'charge:pay', 'system:admin'
]

export const useNotificationStore = defineStore('notification', () => {
  const items = ref<NotificationRecord[]>([])
  const total = ref(0)
  const unreadCount = ref(0)
  const loading = ref(false)
  const error = ref('')
  const useSSE = ref(false)

  /**
   * R-11: 通知为全院级内容,服务端已收紧为仅员工可读/可订阅。
   * 患者路由(patient/*)同样挂在 MainLayout 下,而 MainLayout 会无条件拉取通知 —— 若不在这里拦截,
   * 患者每次进入页面都会拿到 403,并触发 SSE 的退避重连。故在此统一门禁:
   * 非员工直接不请求、不订阅(与服务端策略一致,失败时不再靠"报错"兜底)。
   */
  function canUseNotifications(): boolean {
    const auth = useAuthStore()
    return EMPLOYEE_AUTHORITIES.some(a => auth.authorities?.includes(a))
  }

  async function fetchEvents() {
    const auth = useAuthStore()
    if (!canUseNotifications()) {
      items.value = []
      total.value = 0
      return
    }
    loading.value = true
    error.value = ''
    try {
      // 优先按科室过滤(检查/检验通知精确投递),药品缴费通知(targetDeptId=null)也会返回
      const deptId = auth.departmentId
      if (deptId) {
        const res = await notifyApi.listEventsByDept(deptId)
        items.value = res.items
        total.value = res.total
      } else {
        // 管理员无科室,返回全部
        const res = await notifyApi.listEvents()
        items.value = res.items
        total.value = res.total
      }
    } catch (e) {
      error.value = '通知服务不可用'
    } finally {
      loading.value = false
    }
  }

  function setupSSE() {
    unsubscribe()
    // R-11: 非员工不订阅(与服务端授权策略一致),避免 403 与重连噪音
    if (!canUseNotifications()) {
      return
    }
    const auth = useAuthStore()
    const deptId = auth.departmentId
    subscribeNotifications((record: any) => {
      // 按科室过滤:targetDeptId 为 null 的通知(如药品缴费)所有科室都收
      if (deptId && record.targetDeptId && record.targetDeptId !== deptId) {
        return  // 不是本科室的通知,忽略
      }
      items.value.unshift(record)
      total.value++
      unreadCount.value++
    })
    useSSE.value = true
  }

  function clearUnread() {
    unreadCount.value = 0
  }

  function cleanup() {
    unsubscribe()
  }

  return { items, total, unreadCount, loading, error, useSSE, fetchEvents, setupSSE, clearUnread, cleanup }
})
