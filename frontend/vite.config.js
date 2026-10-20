import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // K6：Element Plus 按需引入。
    //
    // 原来 main.js 里 `app.use(ElementPlus)` 是全量引入，把整套组件的 JS 与
    // CSS 都塞进首屏。这个插件在编译期看到模板里的 <el-xxx> 就**自动**
    // import 对应组件与其样式 —— 页面代码一行不用改。
    //
    // 代价与注意点：
    //   - 组件名是「用到才引入」，所以模板里必须有真实的 <el-xxx> 标签才会被打包；
    //     若某处是通过变量/dynamic 组件用的，需要显式 import。
    //   - ElMessage / ElMessageBox / ElLoading 这类**函数式** API 不走模板解析，
    //     它们的样式需要另外 import（见 main.js 的说明）。
    Components({
      // 只为 element-plus 生效，不扫描本地组件目录（本项目没有约定目录）
      dts: false,
      dirs: [],
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    // K6：端口被占用时直接报错，而不是静默换到 5174。
    // 文档里所有地方都写着 5173，静默换端口会让人对着旧标签页调试到崩溃。
    strictPort: true,
    proxy: {
      // 开发期把 /api 代理到后端，避免前端处理跨域
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 生产不输出 sourcemap（控制台是内部工具，产物不需要可调试）
    sourcemap: false,
    reportCompressedSize: true,
    rollupOptions: {
      output: {
        // 拆 vendor：只把 vue 全家桶单独拎出来。
        //
        // 刻意**不**把 element-plus 也塞进一个 chunk：按需引入的成效会被
        // 「强制归并」掩盖 —— 所有 element 模块反正都在同一个文件里，
        // 看不出哪些组件真的进了包。让 rollup 自己切，观测到的才是真实体积。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('/pinia/')) {
            return 'vue'
          }
          return undefined
        }
      }
    }
  },
  esbuild: {
    // K6：生产去掉 console / debugger。控制台是内部工具，没有需要保留的调试输出。
    drop: ['console', 'debugger']
  }
})
