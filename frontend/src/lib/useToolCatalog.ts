import { createContext, useContext, useEffect, useState } from 'react'
import { tools as toolsApi, type ToolView } from './api'

/**
 * 工具目录：工具自己声明的样子，取一次，进程内缓存。
 *
 * 渲染一次工具调用需要三样东西：用哪个组件、动作词、主语在参数里的哪一项。它们
 * 由工具声明（见后端 `ToolSurface`），界面从这里取一次，于是界面不需要认识任何
 * 工具名。前端按工具名硬编码这些表会随工具增长而过期，症状只是"显示得糙"，
 * 看起来不像 bug。理由见
 * `docs/decisions/architecture/2026-10-06-tool-surface-is-declarative.md`。
 *
 * 声明是静态的，只取一次，也不跟着事件流走：把界面烤进不可变的事件流，将来想改
 * 展示就得迁移历史。
 *
 * 缓存 Promise 而不是结果：两个组件同时挂载只会发一次请求。失败时清掉缓存，
 * 否则一次"还没登录"的 401 会被记一辈子。
 */
let inflight: Promise<ToolView[]> | null = null

function load(): Promise<ToolView[]> {
  inflight ??= toolsApi.list().catch((error: unknown) => {
    inflight = null
    throw error
  })
  return inflight
}

/**
 * 取工具目录。
 *
 * @returns 还没拿到时是 `null`。调用方必须接受 null：那时退化成"显示工具名和
 *          原始参数"，与"没声明形状的工具"是同一套兜底，所以不额外造加载态
 */
export function useToolCatalog(): Map<string, ToolView> | null {
  const [catalog, setCatalog] = useState<Map<string, ToolView> | null>(null)

  useEffect(() => {
    let alive = true
    load()
      .then((list) => {
        if (alive) {
          setCatalog(new Map(list.map((tool) => [tool.name, tool])))
        }
      })
      // 取不到就让调用方走兜底。不弹错：工具目录取不到不影响看对话，
      // 弹错会每次刷新都弹一次。
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])

  return catalog
}

/**
 * 目录往下传的通道。
 *
 * 用 context 而不是逐层传 props：使用者在流的深处（Item → ToolLine → ToolResult →
 * DiffView），逐层传会让每层多背一个无关参数。
 *
 * 默认值是 null（还没有目录），所以不在 Provider 里的组件照样能渲染，只会退化成
 * "显示工具名和原始参数"；单独测小组件时不必先搭 Provider。
 */
export const ToolCatalogContext = createContext<Map<string, ToolView> | null>(null)

/**
 * 这次调用的工具声明。取不到不是错误，是常态（目录还没到，或后端是旧版本），
 * 所以返回 `undefined` 而不是抛，调用方走兜底。
 */
export function useTool(name: string): ToolView | undefined {
  return useContext(ToolCatalogContext)?.get(name)
}
