package com.mqspg.retry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mqspg.retry.entity.RtRetryTask;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** {@code rt_retry_task} 数据访问。自定义语句见 {@code mapper/RtRetryTaskMapper.xml}。 */
public interface RtRetryTaskMapper extends BaseMapper<RtRetryTask> {

    /**
     * 抢占到期任务并授予租约（tech-design §10.5）。
     *
     * <p>使用 {@code FOR UPDATE SKIP LOCKED}，V1 单实例下无竞争，V2 多实例时天然安全。
     *
     * @param limit        单次抢占上限
     * @param leaseSeconds 租约时长（秒）
     * @return 被本次抢占的任务（已被置为 RUNNING）
     */
    List<RtRetryTask> claimDue(@Param("limit") int limit, @Param("leaseSeconds") long leaseSeconds);

    /**
     * 回收租约超时的任务，用于实例崩溃后的自愈。
     *
     * @return 被回收的任务数
     */
    int reclaimExpiredLeases();
}
