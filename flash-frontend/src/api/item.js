import request from './request'

export function getItemDetail(id) {
  return request.get(`/api/item/${id}`)
}
