import { ElMessageBox } from 'element-plus/es/components/message-box/index'

/**
 * 二次确认的统一入口。
 *
 * <p>直接 `await ElMessageBox.confirm(...)` 的问题是：用户点「取消」时它**以 reject 结束**，
 * 而调用方通常在 `async` 的点击处理器里裸 await —— 于是取消变成了一个
 * unhandled rejection，控制台每次点取消都记一条红字。更麻烦的是它让
 * 「用户取消」和「真正的失败」混在同一个 catch 里，容易写出把取消失误当异常处理的分支。
 *
 * <p>这里明确约定：**取消返回 false，确认返回 true**，不抛错。
 * 调用方只需 `if (!(await confirmAction(...))) return`。
 *
 * @param message 正文，说明这一步会造成什么后果
 * @param title   标题
 * @param options 透传给 ElMessageBox 的其余选项（如 confirmButtonText）
 * @returns {Promise<boolean>} 是否确认
 */
export async function confirmAction(message, title, options = {}) {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', ...options })
    return true
  } catch {
    // 取消 / 关闭：都不是错误
    return false
  }
}
