import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'

import { ComposerBox } from '@/components/workspace/ComposerBox'
import {
  errorText,
  apiKeys,
  messages,
  sessions,
  type ConfiguredKey,
  type Project,
  type SessionModel,
} from '@/lib/api'
import { dropSent, noteSent } from '@/lib/pendingEcho'
import { useDoublePress } from '@/lib/useDoublePress'

/** 一条会话用哪个模型：哪一家加一个模型名。没有地址，地址是服务端的事。 */
type Model = { provider: string; modelId: string }

/** 配过的一家，以及问它要回来的模型列表；读不到时列表为空。 */
type Group = { key: ConfiguredKey; models: string[] }

/**
 * 右栏底部的输入框。
 *
 * 第一条会话在用户于空项目里说出第一句话时才创建，所以没有"新建会话"这个动作。
 * 代价是两步之间可能失败（会话建出来、第一句话没发出去），留下一条空会话；可以
 * 接受，重说一次即可。另加"带第一句话的建会话"接口会造出第二条建会话的路。
 *
 * 模型选择器一直显示：会话产生时定下的是起点，不是终点。它改的是往后所有轮次，
 * 下一轮生效；正在跑的那一轮已拿到配置快照，中途改会让"这一轮用了哪个模型"说不清，
 * 所以会话正忙时后端回 409，那句话照原样显示。选择器坐在盒子里面（见
 * {@link ComposerBox}）：用哪个模型是这句话的一部分，不是前置条件；没选之前照样
 * 能打字，只是发不出去。
 */
export function Composer({
  project,
  sessionId,
  currentModel,
  onCreated,
  onModelChanged,
  onRewind,
  initialText,
}: {
  project: Project
  sessionId: string | null
  /** 输入框为空时连按两下 Esc 打开回滚；见下面 hook 的用法 */
  onRewind?: () => void
  /** 回滚之后要把退掉的那句话放回输入框；语义见 {@link ComposerBox} 的 `initialText` */
  initialText?: string
  /** 这条会话现在用哪个模型。从会话列表里拿，不额外发请求 */
  currentModel: SessionModel | null
  onCreated: (sessionId: string) => void
  /** 换过模型之后通知，好让会话列表重拉一遍；不拉的话别处还显示旧值 */
  onModelChanged: () => void
}) {
  const [error, setError] = useState<string | null>(null)

  /**
   * 用户刚改过的模型。
   *
   * 它记着自己属于哪条会话：切走再切回来时靠这个判断还认不认它。不记的话，在 A
   * 会话里换的模型会跟着跑到 B 会话的界面上，而 B 那边没人改过。
   */
  const [edited, setEdited] = useState<{ sessionId: string | null; model: Model } | null>(null)

  /** 界面上显示的模型：用户改过的优先，否则是会话自己那个。 */
  const model: Model | null = edited?.sessionId === sessionId ? edited.model : currentModel

  /**
   * 换一个模型。
   *
   * 还没会话时只是"我打算用谁"，记下来等发送时带走；有会话时是一次真正的请求，
   * 成功了才认账。
   */
  async function changeModel(next: Model) {
    if (sessionId === null) {
      setEdited({ sessionId: null, model: next })
      return
    }

    const back = model === null ? null : { sessionId, model }
    setError(null)
    setEdited({ sessionId, model: next })
    try {
      const updated = await sessions.switchModel(sessionId, next)
      // 以服务端返回的为准：它那边可能归一过配置。
      setEdited({
        sessionId,
        model: { provider: updated.model.provider, modelId: updated.model.modelId },
      })
      onModelChanged()
    } catch (e) {
      // 没换成必须退回原来那个：否则界面会一直挂着并没有生效的模型，
      // 用户会以为下一轮换人了，其实没有。
      setEdited(back)
      setError(errorText(e, '换不了模型'))
    }
  }

  /**
   * 发一句话，不等这一轮跑完。
   *
   * `POST /messages` 会阻塞到整轮结束（几十秒）。等它会让输入框整轮锁着，第二句
   * 发不出去；也会让框里的字等回执才消失，看起来像"发出去了但没清空"。所以发出去
   * 就返回；那一瞬间屏幕上还没有它，由 {@link noteSent} 先画在流末尾（灰的），
   * 真落库后由流接管。
   *
   * 失败时必须说出来：那句话已从输入框清掉，不说就静默地没了。
   */
  async function send(words: string) {
    setError(null)
    try {
      let target = sessionId
      if (target === null) {
        if (model === null) {
          // 界面上本来就发不出去（canSend=false），走到这说明状态不同步；说一句总比静默强。
          setError('先选一个模型')
          return
        }
        // 建会话这一步要等：它很快，而且没有它就没有会话可以画那条回声。
        target = (await sessions.create(project.id, { ...model })).id
        // 钉住模型：列表还没刷新回来，这中间不能让选择器闪空。
        setEdited({ sessionId: target, model })
        onCreated(target)
      }
      const id = target
      // 每次发送一个新标识。它挡的是"同一个请求被送了两遍"（双击、网络重发），
      // 不是"我又说了同一句话"；用内容哈希会让"打断之后原样再发一次"被当成重复
      // 请求挡回。双击由 ComposerBox 的 busy 挡着。
      const clientMessageId = crypto.randomUUID()

      // 先画回声、再发请求：反过来，请求过快时流里那条先到，这里又补一条，
      // 屏幕上会闪出两条一样的。
      noteSent(id, words)
      void messages.send(id, words, clientMessageId).catch((e: unknown) => {
        dropSent(id, words)
        setError(errorText(e))
      })
    } catch (e) {
      setError(errorText(e))
      // 故意吞掉异常：抛出去输入框就会被清空，而这句话根本没发出去。
    }
  }

  /**
   * 连按两下 Esc 打开回滚。
   *
   * 第一次什么都不做：没在跑时按一下 Esc 本来就没有该发生的事。"正在跑"那一路的
   * 打断走会话流那边的另一个 handler，两者不打架。
   */
  const onEscape = useDoublePress(() => onRewind?.())

  return (
    <ComposerBox
      placeholder={sessionId ? '接着说' : '说一句话，开始一条会话'}
      busyLabel="跑着…"
      canSend={model !== null}
      initialText={initialText}
      onEscape={onRewind === undefined ? undefined : onEscape}
      notice={
        error && (
          <p role="alert" className="mb-2 px-1 text-xs text-destructive">
            {error}
          </p>
        )
      }
      onSend={send}
    >
      <ModelPicker chosen={model} onChoose={changeModel} />
    </ComposerBox>
  )
}

// ---------------------------------------------------------------------------
// 选模型
// ---------------------------------------------------------------------------

/** 浮层和触发键之间留的缝。贴着的话像同一块东西断了一截。 */
const GAP = 8

/**
 * 选模型的地方。收起时只有一个按钮，点开是一列按供应商分组的模型。
 *
 * 收起时只显示模型名：供应商是这条会话开在哪儿的背景，模型才是这一句要交给谁，
 * 两者不该一样重。
 *
 * 一列分组而不是左右两列：只配了一家时左列几乎全是空白，而只配一家最常见。分组
 * 标题不需要点选，只回答"下面这几个是谁家的"，一条小字即可；面板宽度由模型名决定。
 *
 * 分组意味着要问所有已配置供应商的模型：N 个并行外部请求（通常一两家）。
 *
 * 它坐在输入框那一行里，所以每种状态都得是一行："正在读""还没配过""拉不到"均只
 * 是一句话，排成段落反而比输入框还重。
 */
function ModelPicker({ chosen, onChoose }: { chosen: Model | null; onChoose: (next: Model) => void }) {
  const [keys, setKeys] = useState<ConfiguredKey[] | null>(null)
  const [groups, setGroups] = useState<Group[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  /** 面板位置。用右边缘对齐触发键：它在右下角，往右展开会顶出屏幕 */
  const [at, setAt] = useState<{ right: number; bottom: number } | null>(null)
  const trigger = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const list = await apiKeys.list()
        if (!alive) return
        setKeys(list)
        if (list.length === 0) return

        const loaded = await Promise.all(
          list.map(async (key) => {
            try {
              const { models } = await apiKeys.models(key.provider)
              return { key, models }
            } catch {
              // 单独一家读不到不该拖垮整个列表：它那一列空着，别家照常显示；
              // 否则用户会以为自己什么都没配。
              return { key, models: [] }
            }
          }),
        )
        if (alive) setGroups(loaded)
      } catch {
        if (alive) setError('读不到你配好的模型')
      }
    })()
    return () => {
      alive = false
    }
  }, [])

  /**
   * 摆面板。
   *
   * useLayoutEffect 在浏览器绘制之前跑完，面板第一次出现就在正确位置；
   * 用 useEffect 会先在错误的地方闪一下再跳过来。
   */
  useLayoutEffect(() => {
    if (!open) return
    const box = trigger.current?.getBoundingClientRect()
    if (!box) return
    setAt({ right: window.innerWidth - box.right, bottom: window.innerHeight - box.top + GAP })
  }, [open])

  if (error) {
    return <span className="px-1 text-xs text-destructive">{error}</span>
  }

  if (keys === null) {
    return <span className="px-1 text-xs text-loom-faint">正在读取模型…</span>
  }

  if (keys.length === 0) {
    return (
      <span className="px-1 text-xs text-loom-faint">
        还没配过模型。先去
        <a href="/settings" className="mx-1 underline underline-offset-2 hover:text-muted-foreground">
          供应商
        </a>
        加一个。
      </span>
    )
  }

  return (
    <>
      <button
        ref={trigger}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((was) => !was)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') setOpen(false)
        }}
        className={`flex max-w-[12rem] shrink-0 items-center gap-1 rounded-md px-1.5 py-1.5 text-xs transition-colors hover:text-foreground ${
          open ? 'bg-selected text-foreground' : 'text-muted-foreground'
        }`}
      >
        <span className="min-w-0 truncate">{chosen?.modelId ?? '选一个模型'}</span>
        {/* 收起朝下、打开翻上去：面板长在按钮上方，箭头跟着指 */}
        <ChevronDown
          className={`size-3 shrink-0 opacity-60 transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open && at && (
        <>
          {/* 透明全屏层：开着时点哪儿都是关掉它。挂 window 监听 click 要处理
              冒泡顺序和重复关闭（同 ContextMenu） */}
          <div className="fixed inset-0 z-40" onPointerDown={() => setOpen(false)} />

          <div
            role="menu"
            aria-label="选模型"
            style={{ right: at.right, bottom: at.bottom }}
            // 描边和阴影二选一：1px 中性描边是阴影的第一层（同 ContextMenu）。
            className="fixed z-50 flex max-h-[min(60vh,20rem)] w-max min-w-[13rem] max-w-[min(22rem,calc(100vw-2rem))] flex-col overflow-y-auto rounded-xl bg-popover p-1 shadow-float"
          >
            {groups?.map((group) => (
              // 组间留 3px：标题跟着上一组最后一行会粘成一坨。
              <section key={group.key.provider} className="mt-[3px] first:mt-0">
                {/* 供应商是分组标题，不是一列选项：不需要点选，只回答"下面这几个
                    是谁家的"。一条小字、中等字重：比正文小但比正文实，抓得住底下几行 */}
                <div className="px-[7px] pb-0.5 pt-1 text-xs font-medium leading-4 text-loom-faint">
                  {group.key.name}
                </div>

                {group.models.length === 0 ? (
                  <div className="px-[7px] py-1.5 text-xs text-loom-faint">读不到这一家的模型</div>
                ) : (
                  group.models.map((id) => {
                    const selected =
                      group.key.provider === chosen?.provider && id === chosen.modelId
                    return (
                      <button
                        key={id}
                        type="button"
                        role="menuitemradio"
                        aria-checked={selected}
                        onClick={() => {
                          onChoose({ provider: group.key.provider, modelId: id })
                          setOpen(false)
                        }}
                        className={`flex min-h-[34px] w-full items-center gap-2 rounded-lg px-[7px] py-[5px] text-left text-xs transition-colors hover:bg-selected ${
                          selected ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'
                        }`}
                      >
                        <span className="min-w-0 flex-1 truncate">{id}</span>
                        {/* 选中靠右边的勾说，不给整行打底色：底色是"鼠标在这儿"，
                            勾是"现在用的是它"，两件事分开 */}
                        {selected && <Check className="size-3.5 shrink-0" />}
                      </button>
                    )
                  })
                )}
              </section>
            ))}
          </div>
        </>
      )}
    </>
  )
}

