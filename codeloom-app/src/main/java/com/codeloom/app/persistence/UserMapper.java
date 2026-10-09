package com.codeloom.app.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code user} 表的读写。
 *
 * <p>表名一律加反引号 —— 图的是全表一个写法。实测（MySQL 8.0.46）：不带反引号的
 * {@code SELECT id FROM user} 和 {@code CREATE TABLE user (…)} 都能过（它不是保留字，
 * 虽然同时是个内置函数名 {@code USER()}，看起来容易误会）。
 */
@Mapper
public interface UserMapper {

    /**
     * 按 **id** 更新这一行；没有这一行时由 {@link #insert} 接手（见 {@code MyBatisUserRepository.save}）。
     *
     * <p><strong>{@code created_at} 不在更新列表里</strong>：它记的是「什么时候注册的」，
     * 不是「这一行最后一次被写是什么时候」。改名改密码不该动它。
     *
     * <h2>为什么不是 {@code INSERT ... ON DUPLICATE KEY UPDATE}</h2>
     * 那张表上有**两个**唯一键（账号、用户名），而那句 SQL 撞上**任何一个**都会去更新那一行 ——
     * 撞在别人的用户名上，它会顺手把**别人**那一行改掉（{@code username = new.username}），
     * 而不是报错。按 id 更新就只认主键，撞名老老实实抛唯一键冲突。
     */
    @Update("""
            UPDATE `user`
            SET username = #{username}, password_hash = #{passwordHash},
                display_name = #{displayName}
            WHERE id = #{id}
            """)
    int update(UserRow row);

    /** 新建一行。**不吞唯一键冲突** —— 撞名该报出去（调用方先查过，这是兜底）。 */
    @Insert("""
            INSERT INTO `user` (id, username, password_hash, display_name, created_at)
            VALUES (#{id}, #{username}, #{passwordHash}, #{displayName}, #{createdAt})
            """)
    int insert(UserRow row);

    @Select("SELECT " + UserRow.COLUMNS + " FROM `user` WHERE id = #{id}")
    UserRow findById(@Param("id") String id);

    /** 登录用。账号上有唯一索引，所以最多一行。 */
    @Select("SELECT " + UserRow.COLUMNS + " FROM `user` WHERE username = #{username}")
    UserRow findByUsername(@Param("username") String username);

    /** 注册时查重。用 COUNT 而不是把整行拉回来 —— 这里只需要知道「有没有」。 */
    @Select("SELECT COUNT(*) FROM `user` WHERE username = #{username}")
    int countByUsername(@Param("username") String username);

    /** 同上，查的是用户名 —— 它也有唯一索引。 */
    @Select("SELECT COUNT(*) FROM `user` WHERE display_name = #{displayName}")
    int countByDisplayName(@Param("displayName") String displayName);
}
