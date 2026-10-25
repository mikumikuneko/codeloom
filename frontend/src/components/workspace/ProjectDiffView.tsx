import { useMemo } from 'react'

import { Hint } from '@/components/workspace/CodeView'
import { DiffView } from '@/components/workspace/SessionStreamView'
import { projects } from '@/lib/api'
import { parseUnifiedDiff } from '@/lib/unifiedDiff'
import { useAsync } from '@/lib/useAsync'

/**
 * 工作区中栏：看某一轮对某个文件做了什么。
 *
 * 它和"看一个文件"是两件事：{@link CodeView} 给这个文件现在的样子，这个给那一轮
 * 对它做了什么。两者共用中栏标签，标签上的改动图标和悬停标题说明是哪一种；光看
 * 正文分不出来。回滚之后后者才显出用处：文件已不是那个样子，只有按提交取的这一段
 * 还看得见。
 *
 * 打开时给整份文件上下文，并落到第一处改动：这是"看这一轮把它改成了什么样"，不是
 * 流里那段只露十二行的摘要。会话流里的内联 diff 仍保持折叠，避免一屏都是代码。
 *
 * 正文按需问，不跟着流过来：事件里只有改了哪些文件、各多少行。正文每次收尾都会
 * 追加，塞进事件等于让每一处读事件的地方都为它买单。见
 * `docs/decisions/feature/2026-10-10-file-diff-is-fetched-on-demand.md`。
 */
export function ProjectDiffView({
  projectId,
  commitSha,
  path,
}: {
  projectId: string
  commitSha: string
  path: string
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
      <div className="min-h-0 flex-1 overflow-auto px-4 pt-2 pb-8">
        {/* 中栏是"看这个文件在这一轮变成了什么样"，不是流里那段摘要：整份铺开，
            默认的 12 行折叠留给会话流里的内联 diff */}
        {data?.truncated === true && (
          <p className="pb-2 text-xs text-loom-faint">只显示了前面一段</p>
        )}
        {loading ? (
          <Hint>正在读取…</Hint>
        ) : error !== null ? (
          <Hint tone="error">{error}</Hint>
        ) : rows.length === 0 ? (
          <Hint>这个文件在这一轮里没有改动。</Hint>
        ) : (
          <DiffView rows={rows} path={path} previewRows={rows.length} focusFirstChange />
        )}
      </div>
    </div>
  )
}
