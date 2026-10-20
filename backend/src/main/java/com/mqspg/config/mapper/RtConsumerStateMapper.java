package com.mqspg.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mqspg.config.entity.RtConsumerState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** {@code rt_consumer_state} 数据访问。 */
public interface RtConsumerStateMapper extends BaseMapper<RtConsumerState> {

    /**
     * 确保状态行存在，并把 {@code bind_version} 更新为最新激活的版本。
     *
     * <p><b>为什么必须是 upsert</b>：原来的实现是「`selectById` 判空 → `insert`」，
     * 并发下发两个 publish / activate 会都读到「不存在」然后各自 insert，
     * 后者撞主键，异常一路变成一句数据库原文（用户完全看不懂发生了什么）。
     *
     * <p><b>为什么顺带更新 bind_version</b>：这个字段名叫「绑定版本」，
     * 但上一版只在首次插入时写一次，之后再激活新版本永远不动 ——
     * 字段名与内容长期不一致，排查时会被误导。这里把它定为
     * 「最近一次状态变更时所处的 active 版本」，
     * 而**不是**「消息绑定版本」（那是重试任务上的 {@code config_version}）。
     */
    @Insert("""
            INSERT INTO rt_consumer_state (route_id, status, reason, bind_version, updated_at)
            VALUES (#{routeId}, 'PAUSED', '等待 Consumer 启动', #{bindVersion}, now())
            ON CONFLICT (route_id) DO UPDATE
                SET bind_version = EXCLUDED.bind_version,
                    updated_at   = now()
            """)
    int upsertBindVersion(@Param("routeId") long routeId, @Param("bindVersion") int bindVersion);
}
