/**
 * 状态 → 标签色的统一映射（K5）。
 *
 * <p>原先四份实现在三个文件里各写各的（dashboard 的 stateTagType、
 * routes 的 consumerTagType / versionTagType、retry 的 tagType），已经产生了
 * **实际不一致**：同一个 PAUSED，总览页显示黄色（warning），
 * 路由页显示红色（danger）。运维在两个页面看到同一状态是两种颜色，
 * 只会以为是两个不同的状态。
 *
 * <p>这里按「状态语义」而不是「页面」来分，同一个状态全局只有一种颜色。
 */

/**
 * 消费者的运行状态（cfg_route 的 consumer_state / rt_consumer_state.state）。
 *
 * <p>语义：RUNNING 正常运行；PAUSED 被人为暂停（不是故障，用黄色提示注意）；
 * RECOVERING 自愈中（黄色）；ERROR 需要人介入（红色）。
 */
const CONSUMER_STATE = {
  RUNNING: 'success',
  PAUSED: 'warning',
  RECOVERING: 'warning',
  ERROR: 'danger'
}

/** 配置版本状态。ACTIVE 生效中、PUBLISHED 已发布未生效、DRAFT 草稿。 */
const VERSION_STATUS = {
  ACTIVE: 'success',
  PUBLISHED: 'primary',
  DRAFT: 'warning'
}

/**
 * 重试任务状态。
 *
 * <p>注意 RUNNING 用 primary 而不是 success：重试任务「正在跑」不代表成功，
 * 用绿色会让人以为已经处理完了。
 */
const RETRY_STATUS = {
  SUCCEEDED: 'success',
  RUNNING: 'primary',
  PENDING: 'warning',
  DLQ: 'danger'
}

/** 未知状态一律 info（灰色），不猜。 */
const UNKNOWN = 'info'

function pick(table, status) {
  return table[status] ?? UNKNOWN
}

export const consumerStateTagType = (state) => pick(CONSUMER_STATE, state)
export const versionStatusTagType = (status) => pick(VERSION_STATUS, status)
export const retryStatusTagType = (status) => pick(RETRY_STATUS, status)
