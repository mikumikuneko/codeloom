import { File as FileIcon, FileDiff, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { ContextMenu, type MenuAction } from '@/components/workspace/ContextMenu'
import { isCodeFile } from '@/lib/highlight'
import { baseName } from '@/lib/path'

/** 中栏一个标签：一个文件，或某一轮对某个文件的改动。 */
export interface EditorTab {
  /** 稳定身份。文件按路径去重，改动按「提交 + 路径」去重 */
  key: string
  kind: 'file' | 'diff'
  path: string
  /** kind 为 diff 时有值 */
  commitSha?: string
  /** 有未保存的修改。只用于关闭前确认，标签上不画标记（IDEA 也不画） */
  dirty?: boolean
}

/**
 * 中栏顶部的一排标签，行为照 IDEA：打开过的文件留在上面，点一个切一个，关掉
 * 当前标签后落到相邻那个；右键能关当前、关其他、关全部。
 *
 * 标签不是浏览器页签：这里不跨项目，也不跨主干/工作区。换项目或切树时调用方会
 * 把这一排清掉，否则同一个路径会指向另一棵树。
 */
export function EditorTabs({
  tabs,
  activeKey,
  onActivate,
  onClose,
  onCloseOthers,
  onCloseAll,
  onReorder,
}: {
  tabs: EditorTab[]
  activeKey: string | null
  onActivate: (key: string) => void
  onClose: (key: string) => void
  onCloseOthers: (key: string) => void
  onCloseAll: () => void
  /** 把 fromKey 拖到 toKey 的前面或后面 */
  onReorder: (fromKey: string, toKey: string, position: 'before' | 'after') => void
}) {
  const [menu, setMenu] = useState<{ x: number; y: number; key: string } | null>(null)
  const [dragging, setDragging] = useState<string | null>(null)
  const [dropAt, setDropAt] = useState<{ key: string; position: 'before' | 'after' } | null>(null)
  const refs = useRef(new Map<string, HTMLDivElement>())

  // 新开的标签可能在横向滚动区外面；切过去时把它带进视野。
  useEffect(() => {
    if (activeKey !== null) {
      refs.current.get(activeKey)?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
    }
  }, [activeKey])

  // IDEA 的关闭当前页签就是 Ctrl+F4；这里只接管这一条，不碰浏览器自己的快捷键。
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'F4' && event.ctrlKey && activeKey !== null) {
        event.preventDefault()
        onClose(activeKey)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [activeKey, onClose])

  const actions = (key: string): MenuAction[] => [
    { label: '关闭', hint: 'Ctrl+F4', onSelect: () => onClose(key) },
    { label: '关闭其他标签页', onSelect: () => onCloseOthers(key) },
    { label: '关闭所有标签页', onSelect: onCloseAll },
  ]

  return (
    <>
      <div
        role="tablist"
        aria-label="打开的文件"
        className="pane-head gap-0.5 overflow-x-auto overflow-y-hidden px-2"
      >
        {tabs.map((tab) => {
          const active = tab.key === activeKey
          // 标签上只放文件名：IDEA 也不把整条路径摊在一排上，完整路径留在 title 里
          const name = baseName(tab.path)
          return (
            <div
              key={tab.key}
              ref={(element) => {
                if (element === null) {
                  refs.current.delete(tab.key)
                } else {
                  refs.current.set(tab.key, element)
                }
              }}
              role="tab"
              aria-selected={active}
              tabIndex={0}
              draggable
              title={tab.kind === 'diff' ? `${tab.path} · 这一轮的改动` : tab.path}
              onClick={() => onActivate(tab.key)}
              onDragStart={(event) => {
                setDragging(tab.key)
                event.dataTransfer.effectAllowed = 'move'
                event.dataTransfer.setData('text/plain', tab.key)
              }}
              onDragEnd={() => {
                setDragging(null)
                setDropAt(null)
              }}
              onDragOver={(event) => {
                if (dragging === tab.key) {
                  return
                }
                event.preventDefault()
                event.dataTransfer.dropEffect = 'move'
                const rect = event.currentTarget.getBoundingClientRect()
                setDropAt({
                  key: tab.key,
                  position: event.clientX > rect.left + rect.width / 2 ? 'after' : 'before',
                })
              }}
              onDragLeave={(event) => {
                if (event.currentTarget.contains(event.relatedTarget as Node | null)) {
                  return
                }
                if (dropAt?.key === tab.key) {
                  setDropAt(null)
                }
              }}
              onDrop={(event) => {
                event.preventDefault()
                const from = event.dataTransfer.getData('text/plain') || dragging
                if (from === null || from === tab.key) {
                  setDragging(null)
                  setDropAt(null)
                  return
                }
                const rect = event.currentTarget.getBoundingClientRect()
                onReorder(
                  from,
                  tab.key,
                  event.clientX > rect.left + rect.width / 2 ? 'after' : 'before',
                )
                setDragging(null)
                setDropAt(null)
              }}
              onContextMenu={(event) => {
                event.preventDefault()
                setMenu({ x: event.clientX, y: event.clientY, key: tab.key })
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault()
                  onActivate(tab.key)
                }
              }}
              className={`group relative flex h-7 min-w-0 max-w-56 shrink-0 cursor-pointer items-center gap-1.5 rounded-md px-2 text-xs transition-colors ${
                dragging === tab.key ? 'opacity-40' : ''
              } ${
                active
                  ? 'bg-card text-foreground'
                  : 'text-muted-foreground hover:bg-accent hover:text-foreground'
              }`}
            >
              {dropAt?.key === tab.key && (
                <span
                  aria-hidden
                  className={`pointer-events-none absolute inset-y-1 w-0.5 rounded-full bg-ring ${
                    dropAt.position === 'before' ? '-left-1' : '-right-1'
                  }`}
                />
              )}
              {tab.kind === 'diff' ? (
                <FileDiff className="size-3.5 shrink-0 text-loom-code" />
              ) : (
                <FileIcon
                  className={`size-3.5 shrink-0 ${
                    isCodeFile(name) ? 'text-loom-code' : 'text-loom-faint'
                  }`}
                />
              )}
              <span className="truncate">{name}</span>
              <button
                type="button"
                draggable={false}
                title="关闭"
                aria-label={`关闭 ${name}`}
                onClick={(event) => {
                  event.stopPropagation()
                  onClose(tab.key)
                }}
                className={`ml-0.5 grid size-4 shrink-0 place-items-center rounded-sm transition-opacity hover:bg-selected ${
                  active
                    ? 'opacity-70 hover:opacity-100'
                    : 'pointer-events-none opacity-0 group-hover:pointer-events-auto group-hover:opacity-70 hover:opacity-100 focus-visible:pointer-events-auto focus-visible:opacity-100'
                }`}
              >
                <X className="size-3" />
              </button>
            </div>
          )
        })}
      </div>

      {menu !== null && (
        <ContextMenu
          x={menu.x}
          y={menu.y}
          label="标签页操作"
          actions={actions(menu.key)}
          onClose={() => setMenu(null)}
        />
      )}
    </>
  )
}
