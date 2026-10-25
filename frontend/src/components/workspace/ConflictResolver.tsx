import { useEffect, useState } from 'react'

import { CodeLines } from '@/components/workspace/CodeLines'
import { Button } from '@/components/ui/button'
import { errorText, merge, type Conflicts, type MergeOutcome } from '@/lib/api'
import { personDot, type PersonSlot } from '@/lib/people'

/**
 * 冲突裁决，在中栏。
 *
 * 放中栏因为裁决要并排看两份内容：右栏默认只有 480px（30rem），并排两份代码没有
 * 意义；中栏本来
 * 就是看代码的地方，也够宽。于是这一栏有两种模式：平时看一个文件，有冲突时切成两份。
 *
 * 两侧标题是「我的」「主干」，不是 git 的 ours/theirs：后端 `ConflictView` 已按
 * 语义归好 `sessionSide` / `mainSide`，且不随合并方向翻转。否则"用我这份"在同步
 * 和合并两个方向下会指向不同东西。
 *
 * 三种出路而不是两种：取一份常常不是对的答案（两人各往同一文件加一个方法时该两个
 * 都留），所以还有"自己写"一路，对应后端 `resolveConflictByContent`。
 */
export function ConflictResolver({
  sessionId,
  conflicts,
  trigger,
  onResolved,
  onAbort,
}: {
  sessionId: string
  conflicts: Conflicts
  /**
   * 这次冲突出自用户点的哪个按钮。
   *
   * 方向（INTO_SESSION / INTO_MAIN）只说"冲突落在哪一步"，而合并会先同步：点
   * 「合并」撞出的冲突方向也是 INTO_SESSION。只用方向说话，刚点合并的人会看到
   * "同步时撞上的"，以为点错了。点击来源更接近他刚做的事。
   *
   * 刷新之后这个值没了（那时确实分不出来），退回按方向说。
   */
  trigger?: 'SYNC' | 'MERGE' | null
  /** 裁掉一个之后的结论。调用方要靠它说清"下一步是什么"，见下面那段 */
  onResolved: (outcome: MergeOutcome) => void
  onAbort: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const current = conflicts.conflicts[0]
  const total = conflicts.conflicts.length

  // 换到下一个冲突时退出编辑态，否则会把上一个文件的内容带过来。
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
      setError(errorText(e))
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
          {/* 方向决定这次裁决收尾的是哪一步，也决定"接下来还要不要点合并"：
              INTO_SESSION = 收尾的是同步（主干还没动），INTO_MAIN = 收尾的就是合回。
              后端特意带出这个信息（见 ConflictView / MergeDirection）；否则用户会
              以为"冲突没了"就等于"合完了" */}
          <span className="mr-1 text-xs text-loom-faint">
            {trigger === 'MERGE'
              ? '合并时撞上的'
              : trigger === 'SYNC'
                ? '同步时撞上的'
                : conflicts.direction === 'INTO_SESSION'
                  ? '同步时撞上的'
                  : '合回主干时撞上的'}
          </span>
          {!editing && (
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground"
              onClick={() => {
                // 从「我这份」开始改最自然：冲突的另一半在右边看得到，可以照着补。
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

/** 「我」永远是 a 号色：这一栏里只有"我"和"主干"，没有第二个人的位置。 */
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
  /** 冲突文件路径。两份是同一个文件的两个版本，所以两边同值；它只用来判语言 */
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
        <CodeLines content={content} path={path} />
      </div>
    </div>
  )
}
