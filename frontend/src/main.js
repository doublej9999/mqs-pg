import { createApp } from 'vue'
import { createPinia } from 'pinia'

// K6：组件改为按需引入（见 vite.config.js 的 unplugin-vue-components）。
//
// 这里**不再** `app.use(ElementPlus)` —— 那会把整套组件塞回首屏 bundle，
// 按需引入就白做了。中文 locale 改由 App.vue 里的 <el-config-provider> 提供。
//
// 但有两类东西插件管不到，必须手动处理：
//   ① **函数式 API 的样式**：ElMessage / ElMessageBox 通过 JS 调用、
//      不出现在模板里，插件解析不到它们，样式会缺失
//      （表现为消息提示没有样式地堆在角落）；
//   ② **指令**：v-loading 用了 7 处，而 unplugin-vue-components 的 resolver
//      只解析组件，不解析指令 —— 不显式注册的话页面上根本不显示加载遮罩，
//      而且不会报错，属于最难发现的那类回归。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
// 走深路径而不是 `from 'element-plus'`：barrel 里 re-export 了全部组件，
// 从 barrel 取一个指令会把整套组件拉回包里（本次实测 el-* 类名几乎全在）。
import { ElLoading } from 'element-plus/es/components/loading/index'
// v-loading 的样式同样不在插件覆盖范围（它只按模板里的 <el-xxx> 引样式）
import 'element-plus/es/components/loading/style/css'

import '@/styles/index.css'

import App from '@/App.vue'
import router from '@/router'

const app = createApp(App)

// K6：不再全量注册 @element-plus/icons-vue。
// 原来那段 for 循环把**全部**图标注册成全局组件，注释理由是
// 「图标用量不大，换取写页面时不必逐个 import」—— 但实际所有页面本来就是
// 显式 import 的（Refresh/Search/Plus/Coin/User/UserFilled/Lock/ArrowDown）。
// 全局注册因此是纯负担：打包时把整套图标都拉进了首屏 bundle。
// 现在统一显式 import；新增页面记得自己 import，否则模板里会是空标签。

app.directive('loading', ElLoading.directive)
app.use(createPinia())
app.use(router)
app.mount('#app')
