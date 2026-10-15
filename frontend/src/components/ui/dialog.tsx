"use client"

import * as React from "react"
import { cn } from "cn"
import { Dialog as DialogPrimitive } from "radix-ui"

import { Button } from "@/components/ui/button"
import { XIcon } from "lucide-react"

function Dialog({
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Root>) {
  return <DialogPrimitive.Root data-slot="dialog" {...props} />
}

function DialogTrigger({
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Trigger>) {
  return <DialogPrimitive.Trigger data-slot="dialog-trigger" {...props} />
}

function DialogPortal({
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Portal>) {
  return <DialogPrimitive.Portal data-slot="dialog-portal" {...props} />
}

function DialogClose({
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Close>) {
  return <DialogPrimitive.Close data-slot="dialog-close" {...props} />
}

function DialogOverlay({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Overlay>) {
  return (
    <DialogPrimitive.Overlay
      data-slot="dialog-overlay"
      className={cn(
        // **遮罩不模糊**：这个产品的正文是**背后那条会话流** —— 弹一个对话框出来时，
        // 你多半还在看它跑到哪儿了。糊上就等于把正在读的东西拿走。
        // 但"不模糊"不等于"不压暗"：black/10 在一片深底上约等于没有，
        // 弹窗和背景糊成一团 —— 压到 60%，视线才知道该去哪
        "fixed inset-0 isolate z-50 bg-black/60 duration-100 data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0",
        className
      )}
      {...props}
    />
  )
}

function DialogContent({
  className,
  children,
  showCloseButton = true,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> & {
  showCloseButton?: boolean
}) {
  return (
    <DialogPortal>
      <DialogOverlay />
      <DialogPrimitive.Content
        data-slot="dialog-content"
        className={cn(
          "fixed top-1/2 left-1/2 z-50 grid w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-4 rounded-2xl border border-border bg-popover p-5 text-sm text-popover-foreground shadow-[0_24px_64px_-16px_rgb(0_0_0/0.8)] duration-100 outline-none sm:max-w-sm data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95",
          className
        )}
        {...props}
      >
        {children}
        {showCloseButton && (
          <DialogPrimitive.Close data-slot="dialog-close" asChild>
            <Button
              variant="ghost"
              className="absolute top-2 right-2"
              size="icon-sm"
            >
              <XIcon
              />
              <span className="sr-only">Close</span>
            </Button>
          </DialogPrimitive.Close>
        )}
      </DialogPrimitive.Content>
    </DialogPortal>
  )
}

function DialogHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    // gap-4：这个框内部的别的缝都是 16 或 20（内容之间 gap-4、卡片内边距 p-5），
    // 只有标题与描述之间原来是 8 —— 那个值是**漏出来的**，不是有意留窄
    <div
      data-slot="dialog-header"
      className={cn("flex flex-col gap-4", className)}
      {...props}
    />
  )
}

function DialogFooter({
  className,
  showCloseButton = false,
  children,
  ...props
}: React.ComponentProps<"div"> & {
  showCloseButton?: boolean
}) {
  return (
    // **页脚不画分隔线、也不铺底。** 它原来带 border-t + bg-muted/40，而在这套令牌下
    // 那层底是画不出来的（--muted #1d1d22 落在 --popover #1a1a1f 上，差 3/255）——
    // 于是屏幕上只剩一条横贯的线，卡片看着像被拦腰截断、按钮掉到了外面。
    // 这里要的是"按钮在卡片里"，不是"卡片下面接了个条"
    //
    // **也不加 mt。** 加了它，页脚上面那条缝就比别处宽（grid 的 gap-4 再加这一截），
    // 于是"标题—描述—按钮"三段里，描述是**贴着标题**的、不在中间。
    // 不加，上下两条缝一样宽，它自然落在正中
    <div
      data-slot="dialog-footer"
      className={cn(
        "-mx-5 -mb-5 flex flex-col-reverse gap-2 px-5 py-4 sm:flex-row sm:justify-end",
        className
      )}
      {...props}
    >
      {children}
      {showCloseButton && (
        <DialogPrimitive.Close asChild>
          <Button variant="outline">Close</Button>
        </DialogPrimitive.Close>
      )}
    </div>
  )
}

function DialogTitle({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Title>) {
  return (
    <DialogPrimitive.Title
      data-slot="dialog-title"
      className={cn(
        // leading-snug 而不是 leading-none：行高等于字号时，标题的下沿**紧贴着字底**，
        // 于是它和下面那行之间的缝看起来比实际值小一截。给标题一点行高，两段才分得开
        "font-heading text-base leading-snug font-medium",
        className
      )}
      {...props}
    />
  )
}

function DialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Description>) {
  return (
    <DialogPrimitive.Description
      data-slot="dialog-description"
      className={cn(
        "text-sm text-muted-foreground *:[a]:underline *:[a]:underline-offset-3 *:[a]:hover:text-foreground",
        className
      )}
      {...props}
    />
  )
}

export {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
  DialogTrigger,
}
