import { useEffect, useRef, useState } from 'react'

import { CodeLines } from '@/components/workspace/CodeLines'
import { Markdown } from '@/components/workspace/Markdown'
import { ApiError, files, type FileContent } from '@/lib/api'
import { languageOfPath } from '@/lib/highlight'

/**
 * 工作区中栏：看一个文件。
 *
 * <h2>为什么行号在单独一列、而且不可选中</h2>
 * 复制代码的人不希望把行号一起复制走。所以行号列是 `select-none`，
 * 代码列才是内容 —— 一行一个 `div`，而不是把行号拼进字符串里。
 *
 * <h2>为什么只渲染前若干行</h2>
 * 后端已经限了 1 MiB，但那仍然可能有两万行 —— 把它们全塞进 DOM 会让**滚动卡住**，
 * 而卡住的观感和"这个编辑器坏了"没有区别。截到一个够用的行数、并**如实说"只显示了前 N 行"**，
 * 比一个假死的页面诚实。
 *
 * <h2>Markdown 默认看渲染，另给一个看源码的口子</h2>
 * 一份 README 是**写给人读的文档**，不是给人读的源码 —— 打开它就是想读它，
 * 所以 .md 默认走 {@link Markdown} 的文档档（标题分级、段落放宽）。
 *
 * <p>但源码那一路不能没有：表格排歪了、嵌套列表没渲染对的时候，唯一的出路就是看原始文本。
 * 所以顶栏上给了一个开关。切过去之后那份源码**照样有颜色** ——
 * 从前它是整片灰的，因为 Darcula 里一条 {@code markup.*} 规则都没有，
 * 那是另一个 bug（见 {@code darcula.ts} 里那段）。
 */
export function CodeView({
  projectId,
  path,
  owner,
  reload = 0,
  trunk = false,
}: {
  projectId: string
  path: string | null
  owner?: string
  /**
   * 磁盘上这一份**可能变了**的信号，由调用方递增。
   *
   * <p>它和左边那棵树用的是同一个来源（见 {@code refreshTree}）：agent 写完文件、
   * 同步、合并、裁决落盘 —— 那四件事都会动磁盘，而这个组件自己什么都看不见。
   * 不接这根线的话，agent 改完一个文件，中栏会一直显示**改动前**的内容，
   * 直到有人手动刷新页面（那正是它从前的样子）。
   */
  reload?: number
  /** 读**主干**上的它 —— 和左边那棵树看同一棵 */
  trunk?: boolean
}) {
  const [file, setFile] = useState<FileContent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  /** Markdown 看源码而不是看渲染。**默认是看渲染** —— 打开一个 README，人是想读它 */
  const [source, setSource] = useState(false)
  /**
   * 上一次**真的拉回来了**的是哪个路径。
   *
   * <p>它区分开两种情况，而这两种在 {@link reload} 上是同一件事：
   * 换了另一个文件（要清空、要显示"正在读取…"）、和同一份内容重拉（**不能清**，
   * 清了整栏会闪一下，而它上一秒还好好的）。
   */
  const loadedPath = useRef<string | null>(null)

  // 换文件就回到默认那一档：在这一份上选了源码，不代表打开下一份时也想看源码
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
    // 只有**换了一份**才清空并进"读取中"。同一份重拉时让它继续显示着 ——
    // 内容到了会直接换掉，中间那几百毫秒不该是一片空白
    if (loadedPath.current !== path) {
      setLoading(true)
      setFile(null)
    }
    setError(null)
    files
      .read(projectId, path, owner, trunk)
      .then((content) => {
        if (cancelled) return
        loadedPath.current = path
        setFile(content)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : '读不到这个文件')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, path, owner, trunk, reload])

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

  // 空文件也要**说**它是空的：一片黑和"读取失败""还没加载完"长得一模一样，
  // 而它只是"里面还没有内容"（右键新建出来的文件就是这样）
  if (file.content === '') {
    return <Hint>空文件。</Hint>
  }

  /**
   * 是不是一份 Markdown。
   *
   * <p>判据用的是 {@link languageOfPath} —— **和源码视图里挑哪个语言高亮是同一个函数**。
   * 另写一个"这段路径是不是 md"的话，两处迟早会对不上一份文件
   *（比如在这儿加了 `.markdown` 而那边忘了）。
   */
  const isMarkdown = languageOfPath(file.path) === 'markdown'

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 和中栏左右那两栏的标签同高 —— 三条顶边落在一条线上，见 index.css 里的 .pane-head */}
      <header className="pane-head gap-3 px-4">
        {/* 路径用等宽：它是"机器说的话"，而且一个真实的路径比"代码"这个标签有用得多 */}
        <span className="truncate font-mono text-xs text-muted-foreground">{file.path}</span>
        {/* 两件事用**间距**分开，不用 `·` 连成一句 ——
            那种"用中点把几段元信息串起来"的写法是模板感的来源之一，
            而且它把两个各自独立的事实（多大 / 是不是被截了）裹成了一句读不下去的话 */}
        <span className="ml-auto flex shrink-0 items-center gap-3 text-xs text-loom-faint">
          {/* 只对 Markdown 出现。**文案写的是"点了会变成什么"**，不是"现在是什么" ——
              一个写着当前状态的按钮，看起来像标签而不像能按的东西 */}
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

      {/* `relative` 不是装饰，**它决定了这个滚动区裁不裁得住绝对定位的内容**：
          裁剪按**包含块**算，不按 DOM 祖先。少了它，内容里任何一个绝对定位、
          又没被自己父级关住的元素（比如 remark-gfm 给脚注标题加的那个 sr-only），
          包含块就会一路落到 <html>，于是它**逃出这个滚动区**、被放在文档流里
          它本该在的位置 —— 整页跟着被撑开几千像素，而每一层容器的高度看上去都正常 */}
      <div className="relative min-h-0 flex-1 overflow-auto px-4 pt-2 pb-8">
        {isMarkdown && !source ? (
          // **左对齐、铺满**，不居中也不夹宽度。这一栏是"看一个文件"的地方 ——
          // 同一栏切到源码就是左边顶格的代码，渲染出来却缩在中间的话，
          // 两档切换会像换了个页面；而且中栏被拖窄时居中会挤出更难看的边距
          <Markdown variant="document">{file.content}</Markdown>
        ) : (
          /* key 挂路径：换一个文件就重建。高亮结果是跟着**某一份内容**走的，
             沿用上一份的 token 会把上一个文件的行上到这一个文件上 —— 上错色比不上色难看得多 */
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

function Hint({ children, tone }: { children: React.ReactNode; tone?: 'error' }) {
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
