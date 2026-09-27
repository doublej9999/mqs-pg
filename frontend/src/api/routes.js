import request from './request'

/** 路由配置与版本 */

export function listRoutes() {
  return request({ url: '/routes', method: 'get' })
}

export function getRoute(routeId) {
  return request({ url: `/routes/${routeId}`, method: 'get' })
}

/** 新建路由：{ name, topic, tag, targetId } */
export function createRoute(data) {
  return request({ url: '/routes', method: 'post', data })
}

/** 修改路由；Topic/Tag 变化会重建消费者 */
export function updateRoute(routeId, data) {
  return request({ url: `/routes/${routeId}`, method: 'put', data })
}

/** @param force 还有未完成重试任务时，默认拒绝；确认丢弃才传 true */
export function deleteRoute(routeId, force = false) {
  return request({ url: `/routes/${routeId}`, method: 'delete', params: { force } })
}

// ---- 草稿 ----

/** 没有草稿时后端会基于生效版本合成一份未持久化的视图 */
export function getDraft(routeId) {
  return request({ url: `/routes/${routeId}/draft`, method: 'get' })
}

export function saveDraft(routeId, data) {
  return request({ url: `/routes/${routeId}/draft`, method: 'put', data })
}

/** 按目标表列 + 同名列自动生成映射草稿 */
export function autoGenerateMappings(routeId) {
  return request({ url: `/routes/${routeId}/draft/auto-generate`, method: 'post' })
}

// ---- 版本 ----

export function listVersions(routeId) {
  return request({ url: `/routes/${routeId}/versions`, method: 'get' })
}

/** 以当前生效版本为模板新建草稿版本 */
export function createVersion(routeId) {
  return request({ url: `/routes/${routeId}/versions`, method: 'post' })
}

export function validateVersion(routeId, version) {
  return request({ url: `/routes/${routeId}/versions/${version}/validate`, method: 'get' })
}

/** 校验不通过时返回 { valid:false, issues:[...] }，而不是抛错 */
export function publishVersion(routeId, version) {
  return request({ url: `/routes/${routeId}/versions/${version}/publish`, method: 'post' })
}

export function deleteVersion(routeId, version) {
  return request({ url: `/routes/${routeId}/versions/${version}`, method: 'delete' })
}

export function activateVersion(routeId, version) {
  return request({
    url: `/routes/${routeId}/versions/${version}/activate`,
    method: 'post'
  })
}

export function rollbackRoute(routeId) {
  return request({ url: `/routes/${routeId}/rollback`, method: 'post' })
}
