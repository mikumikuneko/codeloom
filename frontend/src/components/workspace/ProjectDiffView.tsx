import { useMemo } from 'react'
import { X } from 'lucide-react'

import { Hint } from '@/components/workspace/CodeView'
import { DiffView } from '@/components/workspace/SessionStreamView'
import { projects } from '@/lib/api'
import { parseUnifiedDiff } from '@/lib/unifiedDiff'
import { useAsync } from '@/lib/useAsync'

/**
 * 工作区中栏：看某一轮对某个文件做了什么。
 *
 * 它和"看一个文件"是两件事：{@link CodeView} 给这个文件现在的样子，这个给那一轮
 * 对它做了什么。两者在同一栏出现，所以顶上要说清是哪一种；光看正文分不出来。
 * 回滚之后后者才显出用处：文件已不是那个样子，只有按提交取的这一段还看得见。
 *
 * 正文按需问，不跟着流过来：事件里只有改了哪些文件、各多少行。正文每次收尾都会
 * 追加，塞进事件等于让每一处读事件的地方都为它买单。见
 * `docs/decisions/feature/2026-10-10-file-diff-is-fetched-on-demand.md`。
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
  /** 关掉它，落回 {@link CodeView} 看这个文件现在的样子 */
  onClose: () => void
}) {
  // 拉一段改动 = 标准三件事（加载 / 出错 / 取消），见 useAsync。
  const { data, error, loading } = useAsync(
    () => projects.diff(projectId, commitSha, path),
    [projectId, commitSha, path],
  )
  const rows = useMemo(
    () => (data === null ? [] : parseUnifiedDiff(data.text)),
    [data],
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 和中栏左右两栏的标签同高：三条顶边落在一条线上，见 index.css 的 .pane-head */}
      <header className="pane-head gap-3 px-4">
        <span className="truncate font-mono text-xs text-muted-foreground">{path}</span>
        <span className="ml-auto flex shrink-0 items-center gap-3 text-xs text-loom-faint">
          {/* 顶上必须说这是哪一种：同一栏里"文件的内容"和"这一轮的改动"长得像 */}
          <span>这一轮的改动</span>
          {data?.truncated === true && <span>只显示了前面一段</span>}
          {/* 表头三样里只有它不是字，所以得让人看出来能按 */}
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
