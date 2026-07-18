import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as notifyApi from '@/api/notification'
import type { NotificationRecord } from '@/types/notification'

export const useNotificationStore = defineStore('notification', () => {
  const items = ref<NotificationRecord[]>([])
  const total = ref(0)
  const loading = ref(false)
  const error = ref('')

  async function fetchEvents() {
    loading.value = true
    error.value = ''
    try {
      const res = await notifyApi.listEvents()
      items.value = res.items
      total.value = res.total
    } catch (e) {
      error.value = '通知服务不可用'
    } finally {
      loading.value = false
    }
  }

  return { items, total, loading, error, fetchEvents }
})