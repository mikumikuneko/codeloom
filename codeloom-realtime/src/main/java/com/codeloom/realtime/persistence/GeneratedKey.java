package com.codeloom.realtime.persistence;

/**
 * 带「存储层回填自增主键」的插入参数对象的基类。
 *
 * <p>这是一个 **mixin，不是 is-a** —— {@code ChatMessageInsert} 不是一种
 * {@code GeneratedKey}，它只是需要这个字段。之所以用继承而不是组合，是因为
 * MyBatis 的 {@code keyProperty} 必须指向参数对象上的一个**可写属性路径**：
 * 继承来的 {@code setId} 就在参数对象自己身上，而组合会把它变成
 * {@code keyProperty = "key.id"} 这条路 —— 多一层可写路径，多一处能写错的地方。
 *
 * <h2>为什么需要它：record 收不下回填的主键</h2>
 * 本模块里所有的「行」都是 record（不可变，只有访问器）。但
 * {@code useGeneratedKeys} 的工作方式是**把数据库生成的 id 写回参数对象的属性**，
 * 也就是需要一个 setter —— record 没有，回填无处可去，id 就丢了。
 *
 * <p>所以读写两侧形状不同，不是偷懒：
 * <ul>
 *   <li>读：{@link EventRow} / {@link ChatMessageRow}，record，反正只读。
 *   <li>写：{@link EventInsert} / {@link ChatMessageInsert}，继承本类，因为要被回填。
 * </ul>
 *
 * <p>替代方案（都更差）：
 * <ul>
 *   <li>插完再 {@code SELECT LAST_INSERT_ID()}：两条语句必须走同一条连接才成立，
 *       也就是**必须**在事务里。哪天有人不加事务地调一次，拿到的是 0 或别人的 id，
 *       而且不报错。{@code useGeneratedKeys} 没有这个前提，key 就是那条语句自己返回的。
 *   <li>参数用 {@code Map}：能回填，但 {@code "id"} 会退化成字符串常量，
 *       编译期什么也保证不了。
 * </ul>
 */
public abstract class GeneratedKey {

    /** 由 MyBatis 回填。插入前是 null。 */
    private Long id;

    /** @return 存储层分配的自增主键；插入失败时为 null */
    public Long getId() {
        return id;
    }

    /** 只给 MyBatis 的 {@code keyProperty} 用，业务代码不该碰。 */
    public void setId(Long id) {
        this.id = id;
    }
}
