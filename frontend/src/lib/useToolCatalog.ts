import { createContext, useContext, useEffect, useState } from 'react'
import { tools as toolsApi, type ToolView } from './api'

/**
 * 工具目录：**工具自己声明的样子**，取一次，进程内缓存。
 *
 * <h2>为什么要有它</h2>
 * 渲染一条工具调用要三样东西：用哪个组件、动作词、主语在参数里的哪一项。
 * 从前这三样是**界面自己按工具名**决定的（一张词表、一张"哪些是文件工具"的集合、
 * 一张参数键顺序表）。那三张表的问题不是重复，是**它们会过期** ——
 * 加一个工具时没人记得回来改，而症状只是"这条调用显示得糙"，看起来不像 bug。
 *
 * <p>现在那三样由工具自己声明（见后端 `ToolSurface`），界面从这里取一次。
 * 于是**界面永远不需要认识任何工具名**。
 *
 * <h2>为什么缓存、为什么只取一次</h2>
 * 因为声明是**静态的**：同一个工具的任何一次调用都一样。它不跟着事件流走 ——
 * 那是刻意的，把界面烤进不可变的事件流，将来想改展示就得迁移历史。
 *
 * <p>缓存的是 **Promise 而不是结果**：这样两个组件同时挂载时只会发一次请求。
 * 失败时把缓存清掉 —— 否则一次"还没登录"的 401 会被记一辈子。
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
 * @returns 还没拿到时是 `null`。**调用方必须能接受 null** —— 那时退化成
 *          "显示工具名和原始参数"，和"没声明形状的工具"是同一套兜底，
 *          所以这里不额外造一个加载态
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
      // 取不到就让调用方走兜底那条路。**不弹错**：工具目录取不到不影响看对话，
      // 而这里弹一个错会在每次刷新时都弹一次
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
 * <h2>为什么用 context 而不是逐层传 props</h2>
 * 用它的地方在流的深处（{@code Item → ToolLine → ToolResult → DiffView}），
 * 逐层传的话这条路上每一层都要多背一个和它无关的参数。
 * 这和 {@code LiveTurn} 那条"这一轮还在跑吗"是同一个理由、同一套做法。
 *
 * <p>默认值是 `null`（= 还没有目录），所以**不在 Provider 里的组件照样能渲染** ——
 * 它只会退化成"显示工具名和原始参数"。这让单独测某个小组件时不必先搭一个 Provider。
 */
export const ToolCatalogContext = createContext<Map<string, ToolView> | null>(null)

/**
 * 这一次调用的工具声明。**取不到不是错误，是常态**（目录还没到、或是老版本的后端）——
 * 所以返回 `undefined` 而不是抛，调用方走兜底。
 */
export function useTool(name: string): ToolView | undefined {
  return useContext(ToolCatalogContext)?.get(name)
}
