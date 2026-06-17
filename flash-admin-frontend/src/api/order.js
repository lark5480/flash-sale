import request from './request'

export function getOrders(params) {
  return request.get('/admin/order/list', { params })
}

export function getOrder(id) {
  return request.get(`/admin/order/${id}`)
}

export function payOrder(id) {
  return request.post(`/admin/order/${id}/pay`)
}

export function refundOrder(id) {
  return request.post(`/admin/order/${id}/refund`)
}

export function deleteOrder(id) {
  return request.delete(`/admin/order/${id}`)
}
