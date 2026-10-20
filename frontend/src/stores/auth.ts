import { create } from 'zustand'

import { ApiError, auth as authApi, type AuthUser } from '@/lib/api'

/**
 * 「现在是谁登录着」。
 *
 * 不把登录态存在 localStorage：那件事早就由 cookie 做了。这里存的只是这次会话里
 * 拿到的那份用户信息，用来渲染名字、判断跳不跳登录页——它是缓存，不是凭据。
 *
 * 刷新页面后这里清空，靠 `load()` 重新问后端（`GET /api/auth/me`）。那条路径是
 * 权威的：cookie 还在就还是登录着的，"前端以为登录着、其实 cookie 早过期了"不可能
 * 出现。
 *
 * `status` 有三态而不是两态：`unknown` 是应用刚起来、还没问过后端的那一刻。少了它
 * 会有一个瞬间"没登录"，刷新页面会闪一下登录页再跳回去。
 */
type AuthStatus = 'unknown' | 'anonymous' | 'authenticated'

interface AuthState {
  status: AuthStatus
  user: AuthUser | null

  /** 问一次后端「我是谁」。401 是正常结果（还没登录），不当异常 */
  load: () => Promise<void>
  login: (username: string, password: string) => Promise<void>
  register: (username: string, password: string, displayName: string) => Promise<void>
  logout: () => Promise<void>
}

export const useAuth = create<AuthState>((set) => ({
  status: 'unknown',
  user: null,

  load: async () => {
    try {
      set({ status: 'authenticated', user: await authApi.me() })
    } catch (error) {
      // 只有 401 才意味着"没登录"。别的错误（后端没起来、网关 502）不能当成没
      // 登录：那会把"服务挂了"显示成"请重新登录"，用户照做一百遍也没用。抛出去
      // 让上层显示真正的错误。
      if (error instanceof ApiError && error.status === 401) {
        set({ status: 'anonymous', user: null })
        return
      }
      throw error
    }
  },

  login: async (username, password) => {
    set({ status: 'authenticated', user: await authApi.login(username, password) })
  },

  register: async (username, password, displayName) => {
    set({ status: 'authenticated', user: await authApi.register(username, password, displayName) })
  },

  logout: async () => {
    try {
      await authApi.logout()
    } finally {
      // 无论后端说什么都当成登出成功：用户点了登出，界面就该变成未登录。后端失败
      // 时留着登录态，会让他以为"没登出"，而 cookie 其实可能已经清了。
      set({ status: 'anonymous', user: null })
    }
  },
}))
