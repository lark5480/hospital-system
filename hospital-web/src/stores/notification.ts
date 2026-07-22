import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as notifyApi from '@/api/notification'
import { subscribeNotifications, unsubscribe } from '@/api/notifySSE'
import type { NotificationRecord } from '@/types/notification'
import { useAuthStore } from '@/stores/auth'

export const useNotificationStore = defineStore('notification', () => {
  const items = ref<NotificationRecord[]>([])
  const total = ref(0)
  const unreadCount = ref(0)
  const loading = ref(false)
  const error = ref('')
  const useSSE = ref(false)

  async function fetchEvents() {
    const auth = useAuthStore()
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
