package com.mqspg.writer.fold;

import com.mqspg.common.model.TargetRow;
import com.mqspg.mqs.spi.MqsMessage;
import com.mqspg.writer.batch.BufferedMessage;

/**
 * 一条已成功转换、待折叠的候选记录。
 *
 * @param source 来源消息
 * @param row    转换后的目标行
 */
public record CandidateRecord(BufferedMessage source, TargetRow row) {

    public MessageRef ref() {
        MqsMessage m = source.handle();
        return new MessageRef(m.messageId(), source.configVersion(), m);
    }
}
