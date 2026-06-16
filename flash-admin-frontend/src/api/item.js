import request from './request'

export function getItems(params) {
  return request.get('/admin/item/list', { params })
}

export function getItem(id) {
  return request.get(`/admin/item/${id}`)
}

export function createItem(data) {
  return request.post('/admin/item', data)
}

export function updateItem(data) {
  return request.put('/admin/item', data)
}

export function deleteItem(id) {
  return request.delete(`/admin/item/${id}`)
}
