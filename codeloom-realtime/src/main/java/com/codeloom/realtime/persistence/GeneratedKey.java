package com.codeloom.realtime.persistence;

/**
 * 插入参数对象的基类，只负责承载“存储层回填的自增主键”这一个可写字段。
 *
 * <p>这不是 is-a 关系：{@link ChatMessageInsert} 只是需要这个字段。用继承而不是组合，
 * 是因为 MyBatis 的 {@code keyProperty} 必须指向参数对象上的可写属性路径；继承来的
 * {@code setId} 就在参数对象自身，组合则会变成 {@code key.id} 这样多一层路径。
 *
 * <p>需要可变字段是因为本模块的行对象都是 record，而 {@code useGeneratedKeys} 要把数据库
 * 生成的 id 写回参数对象，需要一个 setter。读侧用 {@link EventRow} /
 * {@link ChatMessageRow} 这类 record，写侧才继承本类。
 *
 * <p>不用“插入后再 SELECT LAST_INSERT_ID()”：两条语句必须在同一连接、也就是同一事务里，
 * 漏了事务会拿到 0 或别人的 id 且不报错。{@code useGeneratedKeys} 的 key 由那条语句自己返回，
 * 没有这个前提。
 */
public abstract class GeneratedKey {

    /** 由 MyBatis 回填；插入前是 null。 */
    private Long id;

    /** @return 存储层分配的自增主键；插入失败时为 null */
    public Long getId() {
        return id;
    }

    /** 只给 MyBatis 的 {@code keyProperty} 使用，业务代码不得调用。 */
    public void setId(Long id) {
        this.id = id;
    }
}
