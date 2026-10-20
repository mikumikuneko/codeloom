/**
 * 「谁」的颜色。
 *
 * 画面上同时有两个人的存在（我的 agent、他的 agent），"这句是谁说的"必须一眼
 * 看得出来。两个人的颜色会出现在会话流、观战、冲突两侧，必须使用同一对颜色；
 * 散着写会出现同一个颜色两种意思，而那种错不会报错，只会让人读错。
 *
 * 按 id 判而不是按当前用户：它们描述的是这两个人，不是"我"和"别人"。观战页面
 * 主视角会反过来，但"某人的 agent 是青的"不跟着变。
 */
export type PersonSlot = 'a' | 'b'

/**
 * 把两个成员分到两个色位。
 *
 * 按 id 排序后取下标：同一个人在任何页面上都是同一个颜色，两个人看到的是同一套。
 * 按"谁是当前用户"分会让人看到的界面对不上，而协作界面最怕这个。用排序而不是
 * 数组顺序：成员集合来自后端，顺序不保证稳定，否则刷新一次颜色就换一次。
 */
export function slotsOf(memberIds: string[]): Map<string, PersonSlot> {
    const sorted = [...memberIds].sort()
    const slots = new Map<string, PersonSlot>()
    sorted.forEach((id, index) => slots.set(id, index === 0 ? 'a' : 'b'))
    return slots
}

/** 色位对应到 Tailwind 的颜色类。集中在这里，免得各处各写一遍字符串。 */
export const personDot: Record<PersonSlot, string> = {
    a: 'bg-loom-a',
    b: 'bg-loom-b',
}
