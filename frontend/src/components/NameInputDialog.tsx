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
 * 「输一个名字，提交」对话框。
 *
 * 新建项目、新建文件、新建目录、重命名共用一份表单：一个输入框、一句说明、一个
 * 提交按钮、一句就地错误。
 *
 * 不控制 open：调用方在要显示时挂它、关掉时卸载它，名字和错误状态随之清掉，不会
 * 留下上次没提交的名字。
 *
 * `onSubmit` 返回一句话就是失败：那句话显示在输入框下面，弹窗不关。失败时关掉会
 * 让用户刚打的字消失，而他正要做的是改一下再试。
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
