import request from './request'

/** 目标表定义：写到哪个数据源的哪个 schema 的哪张表 */

export function listTargets() {
  return request({ url: '/targets', method: 'get' })
}

export function createTarget(data) {
  return request({ url: '/targets', method: 'post', data })
}

export function updateTarget(id, data) {
  return request({ url: `/targets/${id}`, method: 'put', data })
}

export function deleteTarget(id) {
  return request({ url: `/targets/${id}`, method: 'delete' })
}
