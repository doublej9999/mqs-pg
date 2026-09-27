import request from './request'

/** 路由配置与版本 */

export function listRoutes() {
  return request({ url: '/routes', method: 'get' })
}

export function getRoute(routeId) {
  return request({ url: `/routes/${routeId}`, method: 'get' })
}

export function listVersions(routeId) {
  return request({ url: `/routes/${routeId}/versions`, method: 'get' })
}

export function activateVersion(routeId, version) {
  return request({
    url: `/routes/${routeId}/versions/${version}/activate`,
    method: 'post'
  })
}
