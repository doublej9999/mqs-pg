import request from './request'

/** 重试任务与 DLQ */

export function listRetry(params) {
  return request({ url: '/retry', method: 'get', params })
}

export function retrySummary() {
  return request({ url: '/retry/summary', method: 'get' })
}

/**
 * 概览卡片用的形态：键统一转小写。
 *
 * 后端按状态原样输出大写键（`PENDING` / `DLQ` …），那是数据库里的值域，不该为了页面改。
 * 但页面若直接写 `summary.pending` 就会恒为 `undefined` —— 于是就出现了
 * 「明明有积压，卡片却显示 0」这种比报错更危险的假象。
 * 归一化只做**一次**（在这里），页面只认小写这一种形状。
 */
export async function retrySummaryByState() {
  const raw = await retrySummary()
  const out = {}
  for (const [key, value] of Object.entries(raw || {})) {
    out[key.toLowerCase()] = value
  }
  return out
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
