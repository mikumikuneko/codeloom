import { useEffect, useRef, useState } from 'react'

import { CodeLines } from '@/components/workspace/CodeLines'
import { Markdown } from '@/components/workspace/Markdown'
import { errorText, files, type FileContent } from '@/lib/api'
import { languageOfPath } from '@/lib/highlight'

/**
 * 工作区中栏：看一个文件。
 *
 * 行号单独一列且不可选中：复制代码的人不希望把行号一起复制走，代码列才是内容。
 *
 * 只渲染前若干行：后端虽已限 1 MiB，仍可能有两万行，全塞进 DOM 会让滚动卡住；
 * 截到够用的行数并如实说"只显示了前 N 行"，比假死的页面诚实。
 *
 * Markdown 默认看渲染，另给一个看源码的口子：README 是写给人读的文档，打开它
 * 就是想读它，所以 .md 默认走 {@link Markdown} 的文档档。表格排歪、嵌套列表没
 * 渲染对时需要看原始文本，所以顶栏有开关；切过去源码照样有颜色。
 */
export function CodeView({
  projectId,
  path,
  reload = 0,
  trunk = false,
}: {
  projectId: string
  path: string | null
  /**
   * 磁盘上这一份可能变了的信号，由调用方递增。
   *
   * 与左边那棵树同一来源（见 `refreshWorkspace`）：agent 写文件、同步、合并、裁决
   * 落盘都会动磁盘，而这个组件自己看不见。不接这根线，agent 改完文件后中栏会一直
   * 显示改动前的内容，直到有人手动刷新页面。
   */
  reload?: number
  /** 读主干上的它，和左边那棵树看同一棵 */
  trunk?: boolean
}) {
  const [file, setFile] = useState<FileContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  /** Markdown 看源码而不是渲染。默认看渲染：打开 README 时人想读它。 */
  const [source, setSource] = useState(false)
  /**
   * 上一次真的拉回来的是哪个路径。
   *
   * 区分两种情况，它们在 {@link reload} 上是同一件事：换了另一个文件（要清空并
   * 显示"正在读取…"），和同一份内容重拉（不能清，清了整栏会闪一下，而它上一秒
   * 还好好的）。
   */
  const loadedPath = useRef<string | null>(null)

  // 换文件就回到默认档：在这一份上选了源码，不代表下一份也想看源码。
  useEffect(() => {
    setSource(false)
  }, [path])

  useEffect(() => {
    if (!path) {
      setFile(null)
      loadedPath.current = null
      return
    }
    let cancelled = false
    // 只有换了一份才清空并进"读取中"；同一份重拉时继续显示，内容到了直接换掉，
    // 中间那几百毫秒不该是一片空白。
    if (loadedPath.current !== path) {
      setLoading(true)
      setFile(null)
    }
    setError(null)
    files
      .read(projectId, path, trunk)
      .then((content) => {
        if (cancelled) return
        loadedPath.current = path
        setFile(content)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(errorText(e, '读不到这个文件'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, path, trunk, reload])

  if (!path) {
    return <Hint>从左边选一个文件。</Hint>
  }
  if (loading) {
    return <Hint>正在读取…</Hint>
  }
  if (error) {
    return <Hint tone="error">{error}</Hint>
  }
  if (!file) return null

  if (file.binary) {
    return <Hint>这是个二进制文件（{formatSize(file.sizeBytes)}），看不了。</Hint>
  }

  // 空文件也要说它是空的：一片黑与"读取失败""还没加载完"长得一模一样。
  if (file.content === '') {
    return <Hint>空文件。</Hint>
  }

  /**
   * 是不是一份 Markdown。
   *
   * 判据用 {@link languageOfPath}，与源码视图挑语言高亮是同一个函数；另写一个
   * "路径是不是 md"会迟早对不上（如加了 `.markdown` 而那边忘了）。
   */
  const isMarkdown = languageOfPath(file.path) === 'markdown'

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 和中栏左右两栏的标签同高：三条顶边落在一条线上，见 index.css 的 .pane-head */}
      <header className="pane-head gap-3 px-4">
        {/* 路径用等宽：它是机器说的话，而且一个真实路径比"代码"这个标签有用 */}
        <span className="truncate font-mono text-xs text-muted-foreground">{file.path}</span>
        {/* 两件事用间距分开，不用中点连成一句：那会把两个独立事实
            （多大 / 是否截断）裹成一句读不下去的话 */}
        <span className="ml-auto flex shrink-0 items-center gap-3 text-xs text-loom-faint">
          {/* 只对 Markdown 出现。文案写"点了会变成什么"，不是"现在是什么"：
              写着当前状态的按钮看起来像标签，不像能按的东西 */}
          {isMarkdown && (
            <button
              type="button"
              onClick={() => setSource((was) => !was)}
              className="rounded px-1.5 py-0.5 transition-colors hover:bg-selected hover:text-foreground"
            >
              {source ? '看渲染' : '看源码'}
            </button>
          )}
          <span>{formatSize(file.sizeBytes)}</span>
          {file.truncated && <span>已截断</span>}
        </span>
      </header>

      {/* relative 不是装饰，它决定滚动区裁不裁得住绝对定位内容：裁剪按包含块算，
          不按 DOM 祖先。少了它，内容里任何绝对定位又没被父级关住的元素（如
          remark-gfm 给脚注标题加的 sr-only）会把包含块落到 `<html>`，逃出滚动区，
          整页被撑开几千像素，而每一层容器的高度看上去都正常 */}
      <div className="relative min-h-0 flex-1 overflow-auto px-4 pt-2 pb-8">
        {isMarkdown && !source ? (
          // 左对齐、铺满，不居中也不夹宽度：同一栏切到源码是顶格代码，渲染却缩在
          // 中间的话，两档切换像换了个页面；中栏拖窄时居中还会挤出更难看的边距。
          <Markdown variant="document">{file.content}</Markdown>
        ) : (
          /* key 挂路径：换文件就重建。高亮结果跟着某一份内容走，沿用上一份 token
             会把上一个文件的行上到这一个文件。 */
          <CodeLines key={file.path} content={file.content} path={file.path} />
        )}
      </div>
    </div>
  )
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function Hint({ children, tone }: { children: React.ReactNode; tone?: 'error' }) {
  return (
    <div className="pane-absent">
      <p
        role={tone === 'error' ? 'alert' : undefined}
        className={tone === 'error' ? 'text-destructive' : 'text-muted-foreground'}
      >
        {children}
      </p>
    </div>
  )
}
