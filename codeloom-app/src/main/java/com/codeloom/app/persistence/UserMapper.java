package com.codeloom.app.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code user} 表的读写。表名一律加反引号，全表一个写法。
 */
@Mapper
public interface UserMapper {

    /**
     * 按 id 更新这一行；没有这一行时由 {@link #insert} 接手。
     *
     * <p>{@code created_at} 不在更新列表里：它记的是什么时候注册的，改名改密码不该动它。
     *
     * <p>不用 {@code INSERT ... ON DUPLICATE KEY UPDATE}：表上有两个唯一键（账号、用户名），
     * 那句 SQL 撞上任一个都会更新那一行 —— 撞在别人的用户名上会改掉别人的行，而不是报错。
     * 按 id 更新只认主键，撞名老老实实抛唯一键冲突。
     *
     * @param row 用户行
     * @return 受影响行数；0 表示没有这一行
     */
    @Update("""
            UPDATE `user`
            SET username = #{username}, password_hash = #{passwordHash},
                display_name = #{displayName}
            WHERE id = #{id}
            """)
    int update(UserRow row);

    /**
     * 新建一行。不吞唯一键冲突：撞名该报出去（调用方先查过，这里只是兜底）。
     *
     * @param row 用户行
     * @return 受影响行数
     */
    @Insert("""
            INSERT INTO `user` (id, username, password_hash, display_name, created_at)
            VALUES (#{id}, #{username}, #{passwordHash}, #{displayName}, #{createdAt})
            """)
    int insert(UserRow row);

    /**
     * @param id 用户 id
     * @return 用户行；不存在时为 null
     */
    @Select("SELECT " + UserRow.COLUMNS + " FROM `user` WHERE id = #{id}")
    UserRow findById(@Param("id") String id);

    /**
     * 登录用。账号上有唯一索引，所以最多一行。
     *
     * @param username 账号
     * @return 用户行；不存在时为 null
     */
    @Select("SELECT " + UserRow.COLUMNS + " FROM `user` WHERE username = #{username}")
    UserRow findByUsername(@Param("username") String username);

    /**
     * 注册时按账号查重，只返回有没有。
     *
     * @param username 账号
     * @return 匹配行数
     */
    @Select("SELECT COUNT(*) FROM `user` WHERE username = #{username}")
    int countByUsername(@Param("username") String username);

    /**
     * 注册时按用户名查重；用户名也有唯一索引。
     *
     * @param displayName 用户名
     * @return 匹配行数
     */
    @Select("SELECT COUNT(*) FROM `user` WHERE display_name = #{displayName}")
    int countByDisplayName(@Param("displayName") String displayName);
}
