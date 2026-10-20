import request from './request'

/** 原始留存查询（PRD 的 raw_message 表）。 */

export function listRawMessages(params) {
  return request({ url: '/raw', method: 'get', params })
}
