/**
 * 行号列的宽度（border-box，含单元格内的右内边距）。
 *
 * 数字右对齐、列宽固定：数字变多时往左长，正文起点不动。额外留 2ch 给数字两侧的
 * 空白，`gapRem` 是数字与正文之间的间距。
 *
 * 三处代码视图共用这一条规则；diff 的间距比另外两处窄，因为行号后面还要紧跟 +/−
 * 记号，由调用方传 0.5。
 */
export function lineNumberWidth(digits: number, gapRem: number): string {
  return `calc(${Math.max(2, digits) + 2}ch + ${gapRem}rem)`
}
