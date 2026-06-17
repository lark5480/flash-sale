import request from './request'

export function getMyOrders(page = 1, size = 10) {
  return request.get('/api/order/list', { params: { page, size } })
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
