import { lazy, Suspense, useEffect, useRef, useState } from 'react'

import { CodeLines } from '@/components/workspace/CodeLines'
import { ApiError, errorText, files, type FileContent } from '@/lib/api'
import { languageOfPath } from '@/lib/highlight'

/** 编辑器连同语言包单独成块：登录页和项目列表不该为它先下一兆。 */
const CodeEditor = lazy(() =>
  import('@/components/workspace/CodeEditor').then((module) => ({ default: module.CodeEditor })),
)

/**
 * 工作区中栏：看和改一个文件。
 *
 * 代码和文本用 {@link CodeEditor}——行号、语法色、光标、选择和撤销栈都是真的，
 * 点进去就能改，不再有“编辑模式”那一层。写入自动发生：输入停下、失焦或
 * Ctrl/Cmd+S 时保存。
 *
 * 代码和文本共用同一个编辑器。Markdown 也用它，只是多一层所见即所得：同一张
 * 视图里，光标所在行露源码，其余行按渲染结果铺开，和 Typora 一致——这里不再单独
 * 维护一份渲染视图。
 *
 * 保存前把打开时读到的 `baseContent` 交给后端比较：这期间 agent、格式化器或
 * 另一个人改了文件，就拒绝覆盖并要求重新读取，和 Claude Code 的写前保护一致。
 *
 * 截断文件和二进制文件只读：前者拿到的不是全文，写回去会删掉后半段。
 */
export function CodeView({
  projectId,
  path,
  reload = 0,
  trunk = false,
  onDirtyChange,
}: {
  projectId: string
  path: string | null
  /** 这一份有没有未保存的修改；关闭标签前用它决定要不要确认 */
  onDirtyChange?: (dirty: boolean) => void
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
  /** 编辑器里的当前文本；加载完成后就是磁盘那份的副本。 */
  const [draft, setDraft] = useState<string | null>(null)
  /**
   * 打开时读到的那一份内容。
   *
   * 不能在保存时拿 `file.content`：编辑期间 agent 可能改了磁盘，file 会被重拉成
   * 新内容；再拿新内容当 base 就等于替它签了字，会把别人的改动静默盖掉。
   */
  const [baseContent, setBaseContent] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saveConflict, setSaveConflict] = useState(false)
  /** 重新读取的触发器：保存被 409 拒了之后用 */
  const [reloadSeed, setReloadSeed] = useState(0)
  /**
   * 上一次真的拉回来的是哪个路径。
   *
   * 区分两种情况，它们在 {@link reload} 上是同一件事：换了另一个文件（要清空并
   * 显示"正在读取…"），和同一份内容重拉（不能清，清了整栏会闪一下，而它上一秒
   * 还好好的）。
   */
  const loadedPath = useRef<string | null>(null)

  /** 编辑器内容和打开时那份不一样，就是“有未保存修改”。 */
  const dirty = draft !== null && baseContent !== null && draft !== baseContent

  // 回调由父层按标签绑定，每次渲染都是新函数；用 ref 让上报只跟 dirty 变，不跟着
  // 父组件重渲染打转。
  const notifyDirty = useRef(onDirtyChange)
  notifyDirty.current = onDirtyChange
  useEffect(() => {
    notifyDirty.current?.(dirty)
  }, [dirty])

  // 输入停下 700ms 就保存；没有显式的“保存”按钮。保存失败后先停住，避免每 700ms
  // 反复撞同一个 409；再改一个字会清掉错误继续试。
  useEffect(() => {
    if (draft === null || !dirty || saving || saveError !== null) {
      return
    }
    const timer = window.setTimeout(() => void save(), 700)
    return () => window.clearTimeout(timer)
  }, [draft, dirty, saving, saveError])

  // 换文件就把上一份的草稿清掉，别带过去。
  useEffect(() => {
    setDraft(null)
    setBaseContent(null)
    setSaveError(null)
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
  }, [projectId, path, trunk, reload, reloadSeed])

  // 文件读回来、或磁盘内容重拉回来时，若没有本地改动就跟着更新编辑器和基线；
  // 有本地改动时保留草稿，保存时由 baseContent 校验兜底。
  useEffect(() => {
    if (file === null) {
      return
    }
    if (draft === null || draft === baseContent) {
      setDraft(file.content)
      setBaseContent(file.content)
    }
  }, [file, draft, baseContent])

  /** 保存被拒后重新读磁盘；本地草稿会丢，按钮上写清楚。 */
  function rereadFromDisk() {
    setDraft(null)
    setBaseContent(null)
    setSaveError(null)
    setSaveConflict(false)
    setReloadSeed((n) => n + 1)
  }

  /**
   * 自动保存。没有改动就什么都不写；保存请求在飞时再触发也直接跳过，下一次输入会
   * 重新排一次。
   */
  async function save() {
    if (file === null || draft === null || baseContent === null || !dirty || saving) {
      return
    }
    setSaving(true)
    setSaveError(null)
    setSaveConflict(false)
    try {
      await files.save(projectId, file.path, baseContent, draft)
      setFile({ ...file, content: draft })
      setBaseContent(draft)
    } catch (e) {
      const conflict = e instanceof ApiError && e.status === 409
      setSaveConflict(conflict)
      setSaveError(
        conflict
          ? `${errorText(e)}\n文件在这期间被改过；先重新读取，再基于新内容修改。`
          : errorText(e, '保存失败'),
      )
    } finally {
      setSaving(false)
    }
  }

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
    return <Hint>这是个二进制文件，看不了。</Hint>
  }

  const isMarkdown = languageOfPath(file.path) === 'markdown'
  const shown = draft ?? file.content

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 文件级动作只在确实有动作时占一行；代码和 Markdown 默认都没有这一行 */}
      {file.truncated && (
        <div className="flex h-8 shrink-0 items-center justify-end gap-3 px-4 text-xs text-loom-faint">
          <span>已截断</span>
        </div>
      )}

      <div className="relative flex min-h-0 flex-1 flex-col">
        {saveError !== null && (
          <div className="mx-4 mt-2 rounded border border-destructive/40 bg-destructive/10 px-2 py-1.5 text-xs text-destructive">
            <p className="whitespace-pre-wrap">{saveError}</p>
            {saveConflict && (
              <button
                type="button"
                onClick={rereadFromDisk}
                className="mt-1 underline underline-offset-2 hover:text-foreground"
              >
                重新读取（丢弃本地修改）
              </button>
            )}
          </div>
        )}

        {file.truncated ? (
          <div className="min-h-0 flex-1 overflow-auto px-4 pt-2 pb-8">
            <CodeLines key={file.path} content={file.content} path={file.path} />
          </div>
        ) : (
          <Suspense fallback={<Hint>正在打开编辑器…</Hint>}>
            <CodeEditor
              key={file.path}
              value={shown}
              path={file.path}
              livePreview={isMarkdown}
              onChange={(next) => {
                setDraft(next)
                setSaveError(null)
                setSaveConflict(false)
              }}
              onBlur={() => void save()}
              onSave={() => void save()}
            />
          </Suspense>
        )}
      </div>
    </div>
  )
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
