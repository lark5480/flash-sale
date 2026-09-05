import request from './request'

export function getStatsOverview() {
  return request.get('/admin/stats/overview')
}
