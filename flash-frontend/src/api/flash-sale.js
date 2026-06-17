import request from './request'

export function getActiveFlashSales() {
  return request.get('/api/flash-sale/active')
}

export function getFlashSaleDetail(id) {
  return request.get(`/api/flash-sale/${id}`)
}

export function purchase(flashSaleId, captchaId, captchaAnswer) {
  return request.post(`/api/flash-sale/${flashSaleId}/purchase`, null, {
    params: { captchaId, captchaAnswer }
  })
}
