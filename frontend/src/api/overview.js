import request from './request'

/** 总览与消费状态 */

export function getOverview() {
  return request({ url: '/overview', method: 'get' })
}

export function listConsumerStates() {
  return request({ url: '/consumer/state', method: 'get' })
}

export function getPgHealth() {
  return request({ url: '/pg/health', method: 'get' })
}

/** 原始留存查询 */
export function listRawMessages(params) {
  return request({ url: '/raw', method: 'get', params })
}

/** 错误 / DLQ 流水 */
export function listErrors(params) {
  return request({ url: '/errors', method: 'get', params })
}
