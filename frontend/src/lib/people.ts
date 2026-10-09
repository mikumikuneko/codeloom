/**
 * 「谁」的颜色。
 *
 * <h2>为什么这件事该有一个单独的地方</h2>
 * 这个产品画面上永远同时有**两个人**的存在 —— 我的 agent、他的 agent ——
 * 而"这句是谁说的"必须一眼看得出来。那意味着两个人的颜色会出现在很多地方
 * （会话流、观战、冲突的两侧），而它们**必须是同一对颜色**。
 *
 * <p>散着写的话，某处用青表示我、另一处用青表示对方，就会出现"同一个颜色两种意思" ——
 * 而那种错不会报错，只会让人读错。
 *
 * <h2>为什么按 id 判、而不是按"当前用户"</h2>
 * 因为它们描述的是**这两个人**，不是"我"和"别人"：在观战页面上，主视角会反过来，
 * 但"小明的 agent 是青的"这件事不该跟着变。
 */
export type PersonSlot = 'a' | 'b'

/**
 * 把两个成员分到两个色位。
 *
 * <p>按 id 排序之后取下标 —— 于是**同一个人在任何页面上都是同一个颜色**，
 * 而且两个人看到的是同一套（他看到的青和我看到的青是同一个人）。
 * 按"谁是当前用户"来分的话，两个人看到的界面会对不上，而协作界面恰恰最怕这个。
 *
 * <p>用排序而不是"数组顺序"：成员集合来自后端，顺序不保证稳定 —— 那样刷新一次
 * 颜色就换一次，比不区分还糟。
 */
export function slotsOf(memberIds: string[]): Map<string, PersonSlot> {
    const sorted = [...memberIds].sort()
    const slots = new Map<string, PersonSlot>()
    sorted.forEach((id, index) => slots.set(id, index === 0 ? 'a' : 'b'))
    return slots
}

/** 色位对应到 Tailwind 的颜色类。集中在这里，免得各处各写一遍字符串。 */
export const personText: Record<PersonSlot, string> = {
    a: 'text-loom-a',
    b: 'text-loom-b',
}

export const personBorder: Record<PersonSlot, string> = {
    a: 'border-loom-a',
    b: 'border-loom-b',
}

export const personDot: Record<PersonSlot, string> = {
    a: 'bg-loom-a',
    b: 'bg-loom-b',
}
