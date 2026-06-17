import request from './request'

export function login(username, password, captchaId, captchaAnswer) {
  return request.post('/api/auth/login', { username, password, captchaId, captchaAnswer })
}

export function register(data) {
  return request.post('/api/auth/register', data)
}
