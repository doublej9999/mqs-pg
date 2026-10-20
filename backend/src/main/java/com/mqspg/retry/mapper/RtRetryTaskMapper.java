package com.mqspg.retry.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mqspg.retry.entity.RtRetryTask;

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
     * 幂等登记一条待重试任务（见 {@code RtRetryTaskMapper.xml}）。
     *
     * <p>与普通 {@code insert} 的区别：当同一 {@code (route_id, message_id)} 已存在
     * 活跃任务（{@code PENDING}/{@code RUNNING}，即命中部分唯一索引 {@code uk_retry_active}）时，
     * 不再抛唯一约束异常，而是刷新该行的调度时间与错误快照。
     *
     * <p>存在的意义：MQ 重复投递（ACK 丢失后的重投）会让同一条消息再次失败并再次入队，
     * 若直接 insert 会撞唯一索引，导致调用方降级为"不 ACK"，从而"不 ACK → 重投 → 再撞"
     * 的死循环。幂等入队把这种重复投递视为同一次失败。
     *
     * @return 任务主键（新建或被复用的活跃任务）
     */
    Long upsertActive(@Param("task") RtRetryTask task);

    /**
     * 回收租约超时的任务，用于实例崩溃后的自愈。
     *
     * @return 被回收的任务数
     */
    int reclaimExpiredLeases();
    /**
     * 续租（J2）：把仍处于 {@code RUNNING} 的任务租约往后推。
     *
     * <p><b>必须带 {@code status = 'RUNNING'} 条件</b>：领取时整批打同一个租约，
     * 而处理是逐条的 —— 先处理完的已经是 SUCCESS/PENDING，若不带这个条件，
     * 续租会把它们连同租约一起「复活」成看似仍在处理的行。
     *
     * <p>返回受影响行数：与传入 id 数不符说明有任务已不在 RUNNING
     * （被别人回收了），调用方据此停止本轮，避免重复重放。
     *
     * @param ids          本轮领取到的任务 id
     * @param leaseSeconds 新租约时长（秒）
     * @return 实际续租成功的行数
     */
    int renewLease(@Param("ids") List<Long> ids, @Param("leaseSeconds") long leaseSeconds);
}
