import request from './request'

/** 重试任务与 DLQ */

export function listRetry(params) {
  return request({ url: '/retry', method: 'get', params })
}

export function retrySummary() {
  return request({ url: '/retry/summary', method: 'get' })
}

export function getRetryTask(id) {
  return request({ url: `/retry/${id}`, method: 'get' })
}

/** 人工重放：把任务提前到当前到期，由调度器领取 */
export function replayRetryTask(id) {
  return request({ url: `/retry/${id}/replay`, method: 'post' })
}

export function cancelRetryTask(id) {
  return request({ url: `/retry/${id}/cancel`, method: 'post' })
}
