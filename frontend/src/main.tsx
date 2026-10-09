import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { App } from '@/App'
import '@/index.css'

const root = document.getElementById('root')
if (!root) {
  // 拿不到挂载点就没有任何可降级的余地：与其让 React 抛一句
  // "Target container is not a DOM element"，不如在这里说清是哪个 id 不见了
  throw new Error('index.html 里没有 id="root" 的挂载点')
}

createRoot(root).render(
  // StrictMode 在开发模式下会把 effect 跑两遍，用来暴露"没写清理"的 bug。
  // 这个项目里正好有一处会因此显形：SSE 和 WebSocket 的连接 —— 写错的话
  // 开发时就能看到两条连接，而不是等到生产环境才发现订阅泄漏
  <StrictMode>
    <App />
  </StrictMode>,
)
