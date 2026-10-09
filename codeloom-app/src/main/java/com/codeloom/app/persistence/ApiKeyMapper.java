package com.codeloom.app.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;
import java.util.List;

/**
 * {@code user_api_key} 表的读写。**每家一把**（主键是 user_id + provider）。
 *
 * <h2>它故意不返回"行"对象</h2>
 * 别的表都有一个 Row 记录在中间转一手，这里没有 —— 因为这张表的形和领域对象
 * **没有对应关系**（领域里根本没有"密钥"这个东西，那正是设计的一部分）。
 * 一个只为了在两处之间搬运一个字符串的 Row 类型，只会让人以为那里有什么可映射的语义。
 *
 * <p>方法名用 {@code sealedKey} 而不是 {@code apiKey}，是提醒读代码的人：
 * **这一层碰到的永远只有密文**。明文只在 {@code SecretCipher} 的两端各出现一瞬间。
 */
@Mapper
public interface ApiKeyMapper {

    /**
     * 存下（或替换）某个端点的密文。
     *
     * <p><strong>{@code name} 和 {@code base_url} 是可空的，而 upsert 会把它们写成新的
     * 值（包括写回 NULL）。</strong>那是有意的：这两个字段是用户在编辑界面上直接改的，
     * "把名字清空"必须能生效。
     *
     * <p>{@code created_at} 只在插入时写、更新时不碰 —— 和 {@code user} 表同一个道理：
     * 它记的是"第一次在这个端点上配置"的时间，不是"最后一次写这一行"，所以换一把 key
     * **不会**把"我什么时候开始用这家"这件事抹掉。
     */
    @Insert("""
            INSERT INTO user_api_key
                (user_id, provider, name, base_url, sealed_key, created_at, updated_at)
            VALUES
                (#{userId}, #{provider}, #{name}, #{baseUrl}, #{sealedKey}, #{now}, #{now})
            AS new
            ON DUPLICATE KEY UPDATE
                name       = new.name,
                base_url   = new.base_url,
                sealed_key = new.sealed_key,
                updated_at = new.updated_at
            """)
    int save(@Param("userId") String userId,
             @Param("provider") String provider,
             @Param("name") String name,
             @Param("baseUrl") String baseUrl,
             @Param("sealedKey") String sealedKey,
             @Param("now") Instant now);

    @Select("SELECT sealed_key FROM user_api_key "
            + "WHERE user_id = #{userId} AND provider = #{provider}")
    String findSealedKey(@Param("userId") String userId,
                         @Param("provider") String provider);

    @Delete("DELETE FROM user_api_key "
            + "WHERE user_id = #{userId} AND provider = #{provider}")
    int delete(@Param("userId") String userId,
               @Param("provider") String provider);

    /** 配过哪几家。**不查 sealed_key** —— 这个方法没有任何理由碰密文。 */
    @Select("SELECT provider, base_url AS baseUrl, name,"
            + " updated_at AS configuredAt "
            + "FROM user_api_key WHERE user_id = #{userId} ORDER BY provider")
    List<ApiKeyRow> listConfigured(@Param("userId") String userId);
}
