import request from './request'

/** 枚举与元数据（错误阶段 / 状态值域），让页面不必硬编码后端枚举 */

export function getErrorStages() {
  return request({ url: '/meta/error-stages', method: 'get' })
}

export function getStatuses() {
  return request({ url: '/meta/statuses', method: 'get' })
}
