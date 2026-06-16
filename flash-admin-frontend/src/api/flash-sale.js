import request from './request'

export function getFlashSales(params) {
  return request.get('/admin/flash-sale/list', { params })
}

export function getFlashSale(id) {
  return request.get(`/admin/flash-sale/${id}`)
}

export function createFlashSale(data) {
  return request.post('/admin/flash-sale', data)
}

export function updateFlashSale(data) {
  return request.put('/admin/flash-sale', data)
}

export function deleteFlashSale(id) {
  return request.delete(`/admin/flash-sale/${id}`)
}

export function updateFlashSaleStatus(id, status) {
  return request.put(`/admin/flash-sale/${id}/status`, { status })
}
