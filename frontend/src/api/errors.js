import request from './request'

/** 错误 / DLQ 流水。 */

export function listErrors(params) {
  return request({ url: '/errors', method: 'get', params })
}
