package com.codeloom.app.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;
import java.util.List;

/**
 * {@code user_api_key} 表的读写。每家一把，主键是 {@code user_id + provider}。
 *
 * <p>故意不返回行对象：这张表的形状和领域对象没有对应关系，领域里根本没有“密钥”。
 * 方法名用 {@code sealedKey} 而不是 {@code apiKey}：这一层碰到的永远只有密文，
 * 明文只出现在 {@code SecretCipher} 的两端。
 */
@Mapper
public interface ApiKeyMapper {

    /**
     * 存下或替换某个服务商的密文。
     *
     * <p>{@code name} 和 {@code base_url} 可空，upsert 会把它们写成新值，包括写回 NULL：
     * 用户要能把名字清空。{@code created_at} 只在插入时写，换 key 不会抹掉
     * “什么时候开始用这家”。
     *
     * @param userId    用户 id
     * @param provider  服务商标识
     * @param name      显示名，可以为 null
     * @param baseUrl   自定义请求地址，预设服务商为 null
     * @param sealedKey 密文
     * @param now       本次写入时间
     * @return 受影响行数
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

    /**
     * 读某一家的密文。
     *
     * @param userId   用户 id
     * @param provider 服务商标识
     * @return 密文；没有配置时为 null
     */
    @Select("SELECT sealed_key FROM user_api_key "
            + "WHERE user_id = #{userId} AND provider = #{provider}")
    String findSealedKey(@Param("userId") String userId,
                         @Param("provider") String provider);

    /**
     * 删除某一家的密钥。
     *
     * @param userId   用户 id
     * @param provider 服务商标识
     * @return 受影响行数
     */
    @Delete("DELETE FROM user_api_key "
            + "WHERE user_id = #{userId} AND provider = #{provider}")
    int delete(@Param("userId") String userId,
               @Param("provider") String provider);

    /**
     * 配过哪几家。不查 {@code sealed_key}，这个方法没有理由碰密文。
     *
     * @param userId 用户 id
     * @return 已配置的服务商，按 provider 排序
     */
    @Select("SELECT provider, base_url AS baseUrl, name,"
            + " updated_at AS configuredAt "
            + "FROM user_api_key WHERE user_id = #{userId} ORDER BY provider")
    List<ApiKeyRow> listConfigured(@Param("userId") String userId);
}
