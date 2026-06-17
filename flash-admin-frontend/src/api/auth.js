import request from './request'

export function login(username, password, captchaId, captchaAnswer) {
  return request.post('/admin/auth/login', { username, password, captchaId, captchaAnswer })
}
