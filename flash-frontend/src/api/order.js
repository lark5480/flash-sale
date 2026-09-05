import request from './request'

/**
 * 查询我的订单（分页 + 状态筛选 + 关键词搜索）
 *
 * @param {Object} params       查询参数
 * @param {number} params.page  页码，从 1 开始
 * @param {number} params.size  每页条数
 * @param {number} params.status 订单状态，null 表示全部
 * @param {string} params.keyword 关键词，匹配订单号或商品名称
 */
export function getMyOrders({ page = 1, size = 10, status = null, keyword = '' } = {}) {
  const params = { page, size }
  if (status !== null && status !== '') {
    params.status = status
  }
  if (keyword) {
    params.keyword = keyword
  }
  return request.get('/api/order/list', { params })
}

export function getOrderStatus(messageKey) {
  return request.get('/api/order/status', { params: { messageKey } })
}

export function payOrder(id) {
  return request.post(`/api/order/${id}/pay`)
}

export function cancelOrder(id) {
  return request.post(`/api/order/${id}/cancel`)
}

export function refundOrder(id) {
  return request.post(`/api/order/${id}/refund`)
}

export function deleteOrder(id) {
  return request.delete(`/api/order/${id}`)
}
