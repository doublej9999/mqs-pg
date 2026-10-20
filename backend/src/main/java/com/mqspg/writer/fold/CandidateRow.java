package com.mqspg.writer.fold;

import com.mqspg.common.model.TargetRow;

/**
 * 一条「只有目标行、没有来源消息」的折叠候选。
 *
 * <p><b>为什么需要它</b>：重试路径上消息早已 ACK，没有句柄可回执，
 * 也就不该构造 {@link CandidateRecord}（它要求 {@code MqsMessage} 才能生成
 * {@link MessageRef}）。但折叠逻辑本身与「消息从哪来」无关 ——
 * 它只关心目标行和守卫字段。硬塞一个假的 {@code MqsMessage} 会让
 * {@code FoldedRecord.sources()} 非空，而重放路径恰恰依赖它为空的语义
 * （见 {@code RetryReplayer} 类注释）。
 *
 * <p>{@code tag} 仅用于把折叠结果映射回具体是哪一条重试任务。
 */
public record CandidateRow(TargetRow row, Object tag) {
}
