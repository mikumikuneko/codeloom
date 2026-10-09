import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'

import { ComposerBox } from '@/components/workspace/ComposerBox'
import {
  ApiError,
  apiKeys,
  messages,
  sessions,
  type ConfiguredKey,
  type Project,
  type SessionModel,
} from '@/lib/api'
import { dropSent, noteSent } from '@/lib/pendingEcho'
import { useDoublePress } from '@/lib/useDoublePress'

/** 一条会话用哪个模型：哪一家加一个模型名。**没有地址** —— 那是服务端的事。 */
type Model = { provider: string; modelId: string }

/** 配过的一家，以及问它要回来的模型列表。读不到时那个列表是空的。 */
type Group = { key: ConfiguredKey; models: string[] }

/**
 * 右栏底部那个输入框。
 *
 * <h2>「第一条会话」是这样产生的</h2>
 * 用户在一个还没有会话的项目里打了第一句话 —— 那一刻才去建一条会话，然后把它发出去。
 * 于是不需要"新建会话"这个动作，也不需要用户先理解"会话"是什么：
 * **他只是说了句话**。
 *
 * <p>代价是两步之间可能失败（会话建出来了、第一句话没发出去），留下的是一条空会话。
 * 那可以接受 —— 用户已经看见它了，重说一次就行。而加一个"带第一句话的建会话"接口，
 * 会变成**第二条建会话的路**，两条路做同一件事迟早会不一致。
 *
 * <h2>模型选择器为什么一直在</h2>
 * 它从前只在"还没有会话"时出现，理由是"一条会话的模型配置在它产生的那一刻就定下来了"。
 * 那个理由只对了一半 —— **定下来的是起点，不是终点**。会话跑起来之后你总会想换一个：
 * 这轮太贵、这个模型答得不对、想拿强的收个尾。而那时候选择器已经不见了，
 * 唯一的出路是开一条新会话，把上下文整个丢掉重来。
 *
 * <p>所以它现在一直都在。改的是**往后所有轮次**（下一轮生效）—— 正在跑的那一轮
 * 已经拿到配置快照了，中途改靶会让"这一轮用的是哪个模型"说不清。于是会话正忙时
 * 后端回 409，那句话我们照原样显示出来，不自己编一套。
 *
 * <p>它坐在**盒子里面**（见 {@link ComposerBox}）：用哪个模型是这句话的一部分，
 * 不是它的前置条件。所以没选之前照样能打字，只是发不出去。
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
  /** 输入框为空时连按两下 Esc —— 打开回滚。见下面那个 hook 的用法 */
  onRewind?: () => void
  /** 回滚之后要把退掉的那句话放回输入框 —— 语义见 {@link ComposerBox} 的 `initialText` */
  initialText?: string
  /** 这条会话现在用哪个模型。**从会话列表里拿，不额外发请求** —— 那一份数据本来就在 */
  currentModel: SessionModel | null
  onCreated: (sessionId: string) => void
  /** 换过模型之后叫一声，好让会话列表重新拉一遍（不拉的话，别处还显示着旧的那个） */
  onModelChanged: () => void
}) {
  const [error, setError] = useState<string | null>(null)

  /**
   * 用户刚改过的模型。
   *
   * <p>**它记着自己属于哪条会话**：切走再切回来时靠这个判断还认不认它。
   * 不记的话，在 A 会话里换的模型会跟着你跑到 B 会话的界面上 —— 而 B 那边根本没人改过。
   */
  const [edited, setEdited] = useState<{ sessionId: string | null; model: Model } | null>(null)

  /** 界面上要显示的那个：用户改过的优先，否则就是会话自己那个。 */
  const model: Model | null = edited?.sessionId === sessionId ? edited.model : currentModel

  /**
   * 换一个模型。
   *
   * <p>还没会话时它只是"我打算用谁"，记下来等发送时带走；有会话时它是一次真正的
   * 请求，**成了才认账**。
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
      // 以服务端返回的为准：它那边可能归一过地址
      setEdited({
        sessionId,
        model: { provider: updated.model.provider, modelId: updated.model.modelId },
      })
      onModelChanged()
    } catch (e) {
      // 没换成。必须退回原来那个 —— 否则界面上会一直挂着一个**并没有生效**的模型，
      // 而那是这个界面里最坏的一种谎：用户以为下一轮换人了，其实没有
      setEdited(back)
      setError(e instanceof ApiError ? e.message : '换不了模型')
    }
  }

  /**
   * 发一句话。**不等这一轮跑完。**
   *
   * <h2>从前是等的，那是两处毛病的同一个根</h2>
   * {@code POST /messages} 会一直阻塞到整轮结束（几十秒），所以：
   * <ul>
   *   <li>输入框**整轮锁着**（`busy` 为真，第二句根本发不出去）—— 而 Claude Code 和 deepseek-harness 里
   *       干活的时候你照样能发，只是排着队</li>
   *   <li>框里那句话要等回执才消失，看起来像"发出去了但没清空"</li>
   * </ul>
   *
   * <p>所以这里发出去就返回。代价是那一瞬间屏幕上还没有它 —— 由 {@link noteSent}
   * 先画在流末尾（灰的，"还没轮到你"），等它真落库了再让流接管。
   *
   * <p>失败时**必须说出来**：那句话已经从输入框里清掉了，不说的话它就静默地没了。
   */
  async function send(words: string) {
    setError(null)
    try {
      let target = sessionId
      if (target === null) {
        if (model === null) {
          // 界面上本来就发不出去（canSend=false），走到这儿说明状态不同步 —— 说一句总比静默强
          setError('先选一个模型')
          return
        }
        // 建会话这一步**要等**（它很快，而且没有它就没有会话可以画那条回声）
        target = (await sessions.create(project.id, { ...model })).id
        // 钉住模型：列表还没刷新回来，这中间不能让选择器闪空
        setEdited({ sessionId: target, model })
        onCreated(target)
      }
      const id = target
      // **每次发送一个新标识。**
      //
      // 它挡的是"同一个请求被送了两遍"（双击、网络重发），**不是"我又说了同一句话"**。
      // 从前这里拿消息内容的哈希当标识，于是"打断之后原样再发一次"会被后端当成重复
      // 请求挡回去，还回一句让人摸不着头脑的话 —— 而那是用户最自然会做的动作。
      // 双击有 ComposerBox 的 busy 挡着，不需要这一层去兜
      const clientMessageId = crypto.randomUUID()

      // 先画回声、再发请求：反过来的话请求快得离谱时流里那条会先到，
      // 这里随后又补一条，屏幕上会闪出两条一样的
      noteSent(id, words)
      void messages.send(id, words, clientMessageId).catch((e: unknown) => {
        dropSent(id, words)
        setError(e instanceof ApiError ? e.message : '连不上服务器')
      })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '连不上服务器')
      // 故意把异常吞在这里：抛出去输入框就会被清空，而这句话根本没发出去
    }
  }

  /**
   * 连按两下 Esc 打开回滚。
   *
   * <p>**第一次什么都不做** —— Claude Code 也是这么接的（那条路上第一下传的是空函数）。
   * 因为没在跑的时候按一下 Esc 本来就没有该发生的事，而"正在跑"那一路的打断
   * 走的是另一个 handler（在会话流那边，它只在真的在跑时才响应），两者不打架。
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

/** 浮层和触发键之间留的那条缝。贴着的话看起来像同一块东西断了一截。 */
const GAP = 8

/**
 * 选模型的地方。**收起时只有一个按钮，点开是一列按供应商分组的模型。**
 *
 * <h2>为什么收起时只显示模型名</h2>
 * 供应商和模型并排摆在输入框旁边时，那一行读起来是「deepseek │ deepseek-flash │ 发送」——
 * 三个一样重的东西。而它们不是一样重：**供应商是这条会话开在哪儿的背景，
 * 模型才是你这一句要交给谁**。收成一个，「这一轮用哪个模型」才是唯一要看的字。
 *
 * <h2>为什么是一列分组，而不是左边一列供应商、右边一列模型</h2>
 * 左列在**只配了一家**的时候几乎全是空白 —— 而只配一家是最常见的情形。
 * 分组标题不需要点、也不需要选，它只回答"下面这几个是谁家的"，
 * 那就该是一条小字，不该占一列。落到一列上之后，整个面板的宽度由模型名决定，
 * 有几家模型就几行，没有多余的空格子。
 *
 * <p>（这条是照着 deepseek-harness 改的：它把供应商做成**分组标题**而不是一列选项，
 * 选中的那个用**右边一个勾**标出来，不给整行打底色。）
 *
 * <h2>代价：打开页面就把每一家的模型都问一遍</h2>
 * 分组意味着列表里得有**所有**家的模型。所以这是一次真实的、N 个并行的外部请求
 * （N = 配过的供应商数，通常一两家），而不是从前那种"选了哪家才问哪家"。
 *
 * <h2>它坐在输入框那一行里，所以每一种状态都得是一行</h2>
 * 「正在读」「还没配过」「拉不到」原来各是一段话，现在都得能塞进发送键左边那点地方。
 * 这不是妥协：那三件事本来就只是**一句话**，把它们排成段落反而让它们看着比输入框还重。
 */
function ModelPicker({ chosen, onChoose }: { chosen: Model | null; onChoose: (next: Model) => void }) {
  const [keys, setKeys] = useState<ConfiguredKey[] | null>(null)
  const [groups, setGroups] = useState<Group[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  /** 那块面板摆在哪。**用右边缘对齐触发键** —— 它在右下角，往右展开会顶出屏幕 */
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
              // **单独一家读不到不该拖垮整个列表**：它那一列会空着，
              // 而不是让另外几家也跟着消失 —— 那样用户会以为自己什么都没配
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
   * 摆那块面板。
   *
   * <p>{@code useLayoutEffect} 会在**浏览器绘制之前**跑完，所以面板第一次出现时
   * 就已经在正确的位置上 —— 用 {@code useEffect} 的话它会先在错误的地方闪一下再跳过来。
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
        {/* 收起时朝下、打开时翻上去 —— 面板长在这个按钮**上方**，箭头跟着指 */}
        <ChevronDown
          className={`size-3 shrink-0 opacity-60 transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open && at && (
        <>
          {/* 铺一层透明全屏层：开着的时候点哪儿都是"关掉它"，而不是"点到下面那个东西"。
              挂在 window 上监听 click 要处理冒泡顺序和重复关两次，那些都会在某个角落漏掉
              （同 ContextMenu） */}
          <div className="fixed inset-0 z-40" onPointerDown={() => setOpen(false)} />

          <div
            role="menu"
            aria-label="选模型"
            style={{ right: at.right, bottom: at.bottom }}
            // 描边和阴影二选一：1px 中性描边是阴影的第一层（同 ContextMenu）
            className="fixed z-50 flex max-h-[min(60vh,20rem)] w-max min-w-[13rem] max-w-[min(22rem,calc(100vw-2rem))] flex-col overflow-y-auto rounded-xl bg-popover p-1 shadow-float"
          >
            {groups?.map((group) => (
              // 组和组之间留 3px：标题跟着上一组的最后一行，两组会粘成一坨
              <section key={group.key.provider} className="mt-[3px] first:mt-0">
                {/* 供应商是**分组标题**，不是一列选项：它不需要点、也不需要选，
                    只回答"下面这几个是谁家的"。所以它是一条小字、中等字重 ——
                    比正文小，但比正文实，这样它才抓得住底下那几行 */}
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
                        {/* 选中靠**右边这个勾**说，不给整行打底色 ——
                            底色是"鼠标在这儿"，勾是"现在用的是它"，两件事分开 */}
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

