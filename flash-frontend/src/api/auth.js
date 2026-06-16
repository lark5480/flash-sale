import request from './request'

export function login(username, password) {
  return request.post('/api/auth/login', { username, password })
}

export function register(data) {
  return request.post('/api/auth/register', data)
}
