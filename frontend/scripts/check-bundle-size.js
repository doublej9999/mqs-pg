/**
 * 构建产物体积守卫（L5）。
 *
 * <p>为什么需要它：K6 把首屏从 1218 KB 压到 642 KB，但**没有任何东西**阻止
 * 下一个人顺手 `import ElementPlus from 'element-plus'` 又把它拉回去。
 * 体积回归不像功能缺陷那样会被测试发现 —— 它只会安静地变慢，
 * 直到某天有人抱怨「控制台打开要好几秒」。
 *
 * <p>用法：`node scripts/check-bundle-size.js`（在 frontend/ 下跑，需先 build）。
 * 阈值取 K6 实测值再加一点余量，留出正常增长空间；超了就失败并打印明细，
 * 让人一眼看出是哪个文件变大的。
 *
 * <p>刻意**不**做 gzip 估算：Node 自带 zlib 能算，但 gzip 后的体积对
 * 「某个组件被意外全量引入」这类问题不敏感（同一个组件压完差别不大），
 * 原始字节更能反映问题。
 */
import { readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

// K6 交付后的实测基线（见 evolution.md「里程碑 K」条目）：
//   JS 642 KB / CSS 152 KB / 最大单文件 163.77 KB（vue vendor）
// 阈值在基线之上留约 15% 余量：够装下正常的业务增长，又拦得住
// 「又有人全量引入 Element Plus」这种一次性 +300 KB 的回归。
const LIMITS = {
  jsTotalKb: 760,
  cssTotalKb: 190,
  maxChunkKb: 200
}

const assetsDir = join(process.cwd(), 'dist', 'assets')

function sumBy(ext) {
  let total = 0
  let largest = { name: '', size: 0 }
  let count = 0
  let files = []
  try {
    files = readdirSync(assetsDir)
  } catch {
    console.error(`找不到 ${assetsDir} —— 先跑 pnpm build`)
    process.exit(1)
  }
  for (const name of files) {
    if (!name.endsWith(ext)) continue
    const size = statSync(join(assetsDir, name)).size
    total += size
    count += 1
    if (size > largest.size) largest = { name, size }
  }
  return { total, largest, count }
}

const js = sumBy('.js')
const css = sumBy('.css')

const kb = (bytes) => Math.round(bytes / 1024)

const rows = [
  ['JS 总量', kb(js.total), LIMITS.jsTotalKb, `${js.count} 个文件`],
  ['CSS 总量', kb(css.total), LIMITS.cssTotalKb, `${css.count} 个文件`],
  ['最大单文件', kb(js.largest.size), LIMITS.maxChunkKb, js.largest.name]
]

const failures = []
console.log('构建产物体积：')
for (const [label, actual, limit, note] of rows) {
  const ok = actual <= limit
  if (!ok) failures.push(`${label} ${actual} KB > 阈值 ${limit} KB`)
  console.log(
    `  ${ok ? '✓' : '✗'} ${label.padEnd(12)} ${String(actual).padStart(5)} KB / ${limit} KB  ${note}`
  )
}

if (failures.length) {
  console.error('\n体积超出阈值：')
  for (const f of failures) console.error(`  - ${f}`)
  console.error(
    '\n常见原因：\n' +
      '  1. 又出现了 `import ... from \'element-plus\'`（barrel 会拉入全部组件，' +
      '改用 element-plus/es/components/<name>/index 深路径）；\n' +
      '  2. 重新全量注册了 @element-plus/icons-vue；\n' +
      '  3. 关掉了 unplugin-vue-components（见 vite.config.js）。\n' +
      '确认是有意增长后，再调整本文件的 LIMITS 并在 evolution.md 记录原因。'
  )
  process.exit(1)
}

console.log('\n体积在阈值内。')
