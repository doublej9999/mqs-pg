import request from './request'

/** 本地联调用的模拟 MQ 投递入口 */

export function publishMock(payload) {
  return request({ url: '/mock/publish', method: 'post', data: payload })
}

export function mockStatus(routeId) {
  return request({ url: `/mock/status/${routeId}`, method: 'get' })
}
