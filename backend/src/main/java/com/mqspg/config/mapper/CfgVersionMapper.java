package com.mqspg.config.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mqspg.config.entity.CfgVersion;

/** {@code cfg_version} 数据访问。 */
public interface CfgVersionMapper extends BaseMapper<CfgVersion> {

    /**
     * 按 (routeId, version) 读取并**加行锁**，只返回判断状态所需的两列。
     *
     * <p>用于「校验内容 → 按校验结果改状态」这类两步操作：不加锁时，校验与更新之间
     * 可以被一次并发的 {@code saveDraft} 改写 content，
     * 结果等于**把没校验过的内容标成已发布**。
     *
     * <p><b>刻意不 SELECT \*</b>：{@code content} 是 JSONB，靠实体字段上声明的
     * {@code JsonbTypeHandler} 才能映射成 {@code JsonNode}；注解查询走的是 MyBatis 的
     * 自动映射，取到的会是 {@code PGobject} 或 null。之前图省事写成 {@code SELECT *}，
     * 结果 publish 拿到的 content 为 null，校验直接报「内容为空」——
     * 连激活都被连带拒绝。需要内容时请另外用 {@code requireVersion} 取。
     */
    @Select("SELECT id, status FROM cfg_version WHERE route_id = #{routeId} AND version = #{version} FOR UPDATE")
    CfgVersion lockForUpdate(@Param("routeId") long routeId, @Param("version") int version);
}
