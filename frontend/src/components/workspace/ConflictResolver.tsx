import { useEffect, useState } from 'react'

import { CodeLines } from '@/components/workspace/CodeLines'
import { Button } from '@/components/ui/button'
import { ApiError, merge, type Conflicts, type MergeOutcome } from '@/lib/api'
import { personDot, type PersonSlot } from '@/lib/people'

/**
 * 冲突裁决 —— 中栏。
 *
 * <h2>为什么它在中栏，不在右栏</h2>
 * 因为裁决要**并排看两份内容**。右栏只有 26rem，并排两份代码在那个宽度里没有意义；
 * 而中栏本来就是看代码的地方，也本来就够宽。于是这一栏有两种模式：
 * 平时看一个文件，有冲突时切成两份。
 *
 * <h2>两侧的标题是「我的」和「主干」，不是 git 的 ours/theirs</h2>
 * 后端的 `ConflictView` 已经把两侧按**语义**归好了（`sessionSide` / `mainSide`），
 * 而且**不随合并方向翻转**。这对前端很要紧：否则同一句"用我这份"在同步和合并两个
 * 方向下会指向不同的东西 —— 那是最容易写反、出错又最难发现的一处。
 *
 * <h2>三种出路，不是两种</h2>
 * 取一份是最简单的，但它常常不是对的答案：两个人各往同一个文件里加了一个方法，
 * 正确的做法是**两个都留**。所以除了"用这一份"，还必须有一条"我自己写"的路 ——
 * 那是后端 `resolveConflictByContent` 存在的理由，也在这里给出口。
 */
export function ConflictResolver({
  sessionId,
  conflicts,
  onResolved,
  onAbort,
}: {
  sessionId: string
  conflicts: Conflicts
  /** 裁掉一个之后的结论。**调用方要靠它说清"下一步是什么"** —— 见下面那段 */
  onResolved: (outcome: MergeOutcome) => void
  onAbort: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const current = conflicts.conflicts[0]
  const total = conflicts.conflicts.length

  // 换到下一个冲突时退出编辑态 —— 否则会把上一个文件的内容带到下一个
  useEffect(() => {
    setEditing(false)
    setError(null)
  }, [current?.path])

  if (!current) return null

  async function run(action: () => Promise<MergeOutcome>) {
    setBusy(true)
    setError(null)
    try {
      onResolved(await action())
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '连不上服务器')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex h-full flex-col">
      <header className="flex shrink-0 items-center gap-3 px-4 py-2">
        <span className="truncate font-mono text-xs text-muted-foreground">{current.path}</span>
        {total > 1 && <span className="shrink-0 text-xs text-loom-faint">还有 {total} 个</span>}
        <div className="ml-auto flex shrink-0 items-center gap-1">
          {/* 方向决定了**这次裁决收尾的是哪一步**，而它决定了"接下来还要不要点合并"：
              INTO_SESSION = 收尾的是同步（主干还没动），INTO_MAIN = 收尾的就是合回。
              这个信息后端特意带出来了（见 ConflictView / MergeDirection），在这里用上 ——
              否则用户会以为"冲突没了"就等于"合完了" */}
          <span className="mr-1 text-xs text-loom-faint">
            {conflicts.direction === 'INTO_SESSION' ? '同步时撞上的' : '合回主干时撞上的'}
          </span>
          {!editing && (
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground"
              onClick={() => {
                // 从「我这份」开始改最自然：冲突的另一半在右边看得到，可以照着补
                setDraft(current.sessionSide)
                setEditing(true)
              }}
            >
              自己写
            </Button>
          )}
          <Button variant="ghost" size="sm" className="text-muted-foreground" onClick={onAbort}>
            放弃合并
          </Button>
        </div>
      </header>

      {error && (
        <p role="alert" className="shrink-0 px-4 pb-2 text-sm text-destructive">
          {error}
        </p>
      )}

      {editing ? (
        <div className="flex min-h-0 flex-1 flex-col gap-2 px-4 pb-4">
          <p className="text-xs text-loom-faint">
            写下这个文件最后该长什么样。两边的内容在右边那份里看得到。
          </p>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            spellCheck={false}
            className="min-h-0 flex-1 resize-none rounded-md bg-secondary p-3 font-mono text-[13px]
                       leading-6 outline-none focus:ring-2 focus:ring-ring/40"
          />
          <div className="flex gap-2">
            <Button
              size="sm"
              disabled={busy}
              onClick={() => void run(() => merge.keepContent(sessionId, current.path, draft))}
            >
              保存这一份
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setEditing(false)}>
              返回
            </Button>
          </div>
        </div>
      ) : (
        <div className="grid min-h-0 flex-1 grid-cols-2 gap-px bg-border">
          <Side
            title="我的"
            dot={personDot[MY_SLOT]}
            path={current.path}
            content={current.sessionSide}
            disabled={busy}
            onTake={() => void run(() => merge.keep(sessionId, current.path, 'session'))}
          />
          <Side
            title="主干"
            path={current.path}
            content={current.mainSide}
            disabled={busy}
            onTake={() => void run(() => merge.keep(sessionId, current.path, 'main'))}
          />
        </div>
      )}
    </div>
  )
}

/** 「我」永远是 a 号色 —— 这一栏里只有"我"和"主干"，没有第二个人的位置。 */
const MY_SLOT: PersonSlot = 'a'

function Side({
  title,
  dot,
  path,
  content,
  disabled,
  onTake,
}: {
  title: string
  /** 有圆点的是"哪个人"；主干不是人，所以没有 */
  dot?: string
  /** 冲突文件的路径。**两份是同一个文件的两个版本**，所以两边给的是同一个值 —— 它只用来判语言 */
  path: string
  content: string
  disabled: boolean
  onTake: () => void
}) {
  return (
    <div className="flex min-h-0 flex-col bg-background">
      <div className="flex shrink-0 items-center gap-1.5 px-4 py-2">
        {dot && <span className={`inline-block size-1.5 rounded-full ${dot}`} />}
        <span className="text-xs text-muted-foreground">{title}</span>
        <Button
          variant="ghost"
          size="sm"
          disabled={disabled}
          className="ml-auto text-muted-foreground"
          onClick={onTake}
        >
          用这一份
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto px-4 pb-4">
        <CodeLines content={content} path={path} copyable={false} />
      </div>
    </div>
  )
}
