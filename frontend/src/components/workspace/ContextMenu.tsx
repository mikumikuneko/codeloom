import { useEffect, useLayoutEffect, useRef, useState } from 'react'

/** 菜单里的一项。 */
export interface MenuAction {
  label: string
  onSelect: () => void
  /** 破坏性动作。平时是普通颜色，指上去才变红 —— 它该被认出来，但不该先喊 */
  danger?: boolean
  /** 在它**上面**画一条分组线 */
  divided?: boolean
}

/** 卡片和窗口边之间留的空隙。 */
const MARGIN = 8

/**
 * 右键菜单。
 *
 * <h2>它摆在哪</h2>
 * **左上角就在你右键的那一点上** —— 那是右键菜单唯一自然的锚点：
 * 菜单是从你指的地方长出来的，不是从某个角落弹出来的。
 *
 * <p>只有一种情况例外：靠窗口右下角右键时，卡片会有一半在屏幕外。那时候它翻回来，
 * 保证整张都在窗口里。**这个翻转让它不再"就在那儿"**，但不翻的话那半张菜单
 * 是真的点不到。
 *
 * <h2>为什么它自己吃掉整屏的点击</h2>
 * 菜单开着的时候，点任何地方都该是"关掉它"，而不是"点到下面那个东西"。
 * 铺一层透明的全屏层是最简单也最不会漏的做法 —— 挂在 window 上监听 click
 * 要处理冒泡顺序和"重复关两次"，而这些都会在某个角落里漏掉一次。
 */
export function ContextMenu({
  x,
  y,
  actions,
  label,
  onClose,
}: {
  x: number
  y: number
  actions: MenuAction[]
  label: string
  onClose: () => void
}) {
  const card = useRef<HTMLDivElement>(null)
  const [at, setAt] = useState({ left: x, top: y })
  const [active, setActive] = useState(0)

  // 先按右键那一点摆好，量出真实尺寸，再夹回窗口里。
  // useLayoutEffect 在**浏览器绘制之前**跑，所以这个修正是看不见的 ——
  // 用 useEffect 的话卡片会先闪一下再跳回来
  useLayoutEffect(() => {
    const box = card.current?.getBoundingClientRect()
    if (!box) {
      return
    }
    setAt({
      left: Math.max(MARGIN, Math.min(x, window.innerWidth - box.width - MARGIN)),
      top: Math.max(MARGIN, Math.min(y, window.innerHeight - box.height - MARGIN)),
    })
  }, [x, y])

  // 拉开就把焦点收进卡片，否则方向键没有落脚的地方
  useEffect(() => {
    card.current?.focus()
  }, [])

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : actions.length - 1
      setActive((current) => (current + step) % actions.length)
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      actions[active]?.onSelect()
      onClose()
    }
  }

  return (
    <>
      <div
        className="fixed inset-0 z-40"
        onPointerDown={onClose}
        // 在别处再右键一次也是"关掉它"（同时挡掉浏览器那个自带的菜单）
        onContextMenu={(event) => {
          event.preventDefault()
          onClose()
        }}
      />
      <div
        ref={card}
        role="menu"
        tabIndex={-1}
        aria-label={label}
        style={{ left: at.left, top: at.top }}
        onKeyDown={onKeyDown}
        // **描边和阴影二选一**：那块浮层原来两样都有（一条 border + 一层 shadow）。
        // 现在那条 1px 的中性描边改成阴影的**第一层** —— 看上去一模一样，
        // 但只有一个机制在画它。两套一起上迟早会走岔（改了颜色忘了改另一边）
        className="fixed z-50 min-w-44 rounded-lg bg-popover py-1 shadow-float outline-none"
      >
        {actions.map((action, index) => (
          <div key={action.label}>
            {action.divided && <div className="my-1 h-px bg-border" />}
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                action.onSelect()
                onClose()
              }}
              // 鼠标划过就换高亮，键盘也走同一条路 —— 两个入口一套状态，
              // 免得"鼠标停在这、方向键选的是另一个"
              onPointerEnter={() => setActive(index)}
              className={`flex w-full items-center px-3 py-1.5 text-left text-sm transition-colors ${
                index === active ? 'bg-selected' : ''
              } ${action.danger && index === active ? 'text-destructive' : ''}`}
            >
              {action.label}
            </button>
          </div>
        ))}
      </div>
    </>
  )
}
