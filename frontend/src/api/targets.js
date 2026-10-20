import request from './request'

/** 目标表定义：写到哪个数据源的哪个 schema 的哪张表 */

// 预留：当前界面没有「目标表列表」页，目标表随路由表单内联创建/更新。
// 后端接口是存在的（GET /api/targets），保留以便将来做独立的表管理页。
// 注意：下面的 createTarget / updateTarget **是在用的**（routes/index.vue），不要一起删。
export function listTargets() {
  return request({ url: '/targets', method: 'get' })
}

export function createTarget(data) {
  return request({ url: '/targets', method: 'post', data })
}

export function updateTarget(id, data) {
  return request({ url: `/targets/${id}`, method: 'put', data })
}

// 预留：删除目标表目前由删除路由级联完成，界面没有单独的入口。
export function deleteTarget(id) {
  return request({ url: `/targets/${id}`, method: 'delete' })
}
