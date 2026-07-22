import * as visitApi from '@/api/visit'
import type { Order, VisitCreatePayload, VisitDetail } from '@/types/visit'
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useVisitStore = defineStore('visit', () => {
  const visits = ref<VisitDetail[]>([])
  const detail = ref<VisitDetail | null>(null)
  const loading = ref(false)

  // Pagination state
  const total = ref(0)
  const pageNum = ref(1)
  const pageSize = ref(10)
  const keyword = ref('')

  async function fetchList() {
    loading.value = true
    try {
      const result = await visitApi.listVisitsPage(keyword.value, pageNum.value, pageSize.value)
      visits.value = result.items
      total.value = result.total
    } finally {
      loading.value = false
    }
  }

  async function create(payload: VisitCreatePayload) {
    const created = await visitApi.createVisit(payload)
    await fetchList()
    return created
  }

  async function fetchDetail(id: number) {
    loading.value = true
    try {
      detail.value = await visitApi.getVisitDetail(id)
    } finally {
      loading.value = false
    }
  }

  async function addOrder(
    id: number,
    payload: Pick<Order, 'type' | 'itemName' | 'quantity' | 'unitPrice' | 'executionDeptId'>
  ) {
    detail.value = await visitApi.addOrder(id, payload)
  }

  async function updateOrder(
    id: number,
    orderId: number,
    payload: Pick<Order, 'type' | 'itemName' | 'quantity' | 'unitPrice'>
  ) {
    detail.value = await visitApi.updateOrder(id, orderId, payload)
  }

  async function cancelOrder(id: number, orderId: number) {
    detail.value = await visitApi.cancelOrder(id, orderId)
  }

  async function refundOrder(id: number, orderId: number) {
    detail.value = await visitApi.refundOrder(id, orderId)
  }

  async function pay(id: number) {
    detail.value = await visitApi.payVisit(id)
  }

  async function confirm(id: number) {
    detail.value = await visitApi.confirmVisit(id)
  }

  async function finishVisit(id: number, force = false) {
    detail.value = await visitApi.finishVisit(id, force)
  }

  async function deleteVisit(id: number) {
    await visitApi.deleteVisit(id)
    await fetchList()
  }

  return { visits, detail, loading, total, pageNum, pageSize, keyword, fetchList, create, fetchDetail, addOrder, updateOrder, cancelOrder, refundOrder, pay, confirm, finishVisit, deleteVisit }
})