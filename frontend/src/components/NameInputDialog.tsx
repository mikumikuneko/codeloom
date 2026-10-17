import { useId, useState, type FormEvent } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/**
 * 「输一个名字，提交」的对话框。
 *
 * <h2>为什么抽出来</h2>
 * 新建项目、新建文件、新建目录、重命名 —— 四件事的表单是同一个：一个输入框、
 * 一句说明、一个提交按钮、一句就地错误。各写一份已经分叉了（有的有"算了"、
 * 有的没有；错误一个由组件自己接、一个由调用方返回）。
 *
 * <h2>为什么不控制 open</h2>
 * 调用方在"要显示"的时候挂它、关掉时卸载它 —— 名字和错误状态跟着一起清掉，
 * 不会出现"上次关掉时没提交的名字还在框里"。{@link CreateProjectDialog} 和
 * FileTree 里的用法都是这个形状。
 *
 * <p>{@code onSubmit} **返回一句话就是失败**：那句话显示在输入框下面，弹窗不关。
 * 失败时关掉的话，用户刚打的字就没了，而他正要做的是改一下再试。
 */
export function NameInputDialog({
  title,
  description,
  label = '名字',
  initial = '',
  submitLabel,
  busyLabel = '正在提交…',
  onSubmit,
  onClose,
}: {
  title: string
  description: string
  label?: string
  initial?: string
  submitLabel: string
  /** 提交中按钮上那句。默认"正在提交…" */
  busyLabel?: string
  onSubmit: (value: string) => Promise<string | null>
  onClose: () => void
}) {
  const id = useId()
  const [value, setValue] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const trimmed = value.trim()

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    const problem = await onSubmit(trimmed)
    if (problem) {
      setError(problem)
    }
    setBusy(false)
  }

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>

          <div className="space-y-2 py-4">
            <Label htmlFor={id}>{label}</Label>
            <Input
              id={id}
              value={value}
              onChange={(event) => setValue(event.target.value)}
              autoFocus
              required
            />
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
          </div>

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose} disabled={busy}>
              算了
            </Button>
            <Button type="submit" disabled={busy || trimmed === ''}>
              {busy ? busyLabel : submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
