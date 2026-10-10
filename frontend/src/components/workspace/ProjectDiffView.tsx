import { useEffect, useMemo, useState } from 'react'
import { X } from 'lucide-react'

import { Hint } from '@/components/workspace/CodeView'
import { DiffView } from '@/components/workspace/SessionStreamView'
import { ApiError, projects } from '@/lib/api'
import { parseUnifiedDiff } from '@/lib/unifiedDiff'

/**
 * 工作区中栏：看**某一轮**对某个文件做了什么。
 *
 * <h2>它和"看一个文件"是两件事</h2>
 * {@link CodeView} 给的是这个文件**现在**的样子。这个给的是**那一轮对它做了什么** ——
 * 两者在同一栏里出现，所以顶上要说清是哪一种；光看正文是分不出来的。
 *
 * <p>后者在**回滚之后**才显出用处：退回去之后，文件已经不是那个样子了，
 * 只有按提交取的这一段还看得见。
 *
 * <h2>为什么正文是去问来的，而不是跟着流过来的</h2>
 * 事件里只有"改了哪些文件、各多少行"。正文按需取 —— 它每次收尾都会追加，
 * 而把它塞进事件等于让每一处读事件的地方都为它买单。见后端那条决策。
 */
export function ProjectDiffView({
  projectId,
  commitSha,
  path,
  onClose,
}: {
  projectId: string
  commitSha: string
  path: string
  /** 关掉它 —— 关完落回"{@link CodeView}"，看这个文件现在的样子 */
  onClose: () => void
}) {
  const [text, setText] = useState<string | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    projects
      .diff(projectId, commitSha, path)
      .then((result) => {
        if (cancelled) return
        setText(result.text)
        setTruncated(result.truncated)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : '取不到这一段改动')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, commitSha, path])

  const rows = useMemo(() => (text === null ? [] : parseUnifiedDiff(text)), [text])

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 和中栏左右那两栏的标签同高 —— 三条顶边落在一条线上，见 index.css 里的 .pane-head */}
      <header className="pane-head gap-3 px-4">
        <span className="truncate font-mono text-xs text-muted-foreground">{path}</span>
        <span className="ml-auto flex shrink-0 items-center gap-3 text-xs text-loom-faint">
          {/* 顶上必须说这是哪一种 —— 同一栏里"文件的内容"和"这一轮的改动"长得像 */}
          <span>这一轮的改动</span>
          {truncated && <span>只显示了前面一段</span>}
          {/* 表头那三样都是字，只有这一个不是 —— 所以它得让人看出来是能按的 */}
          <button
            type="button"
            title="关掉，看它现在的样子"
            aria-label="关掉，看它现在的样子"
            onClick={onClose}
            className="rounded p-0.5 transition-colors hover:bg-selected hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        </span>
      </header>

      <div className="min-h-0 flex-1 overflow-auto px-4 pt-2 pb-8">
        {/* 表头不跟着这几档走：它是关掉这条路的唯一入口，四档里都得在 */}
        {loading ? (
          <Hint>正在读取…</Hint>
        ) : error !== null ? (
          <Hint tone="error">{error}</Hint>
        ) : rows.length === 0 ? (
          <Hint>这个文件在这一轮里没有改动。</Hint>
        ) : (
          <DiffView rows={rows} path={path} />
        )}
      </div>
    </div>
  )
}
