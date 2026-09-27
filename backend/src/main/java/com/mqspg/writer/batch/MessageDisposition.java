package com.mqspg.writer.batch;

import com.mqspg.mqs.spi.MqsMessage;

/**
 * 一条消息的处置结论。
 *
 * @param handle 平台句柄
 * @param kind   去向
 * @param detail 说明，用于日志与 Console
 */
public record MessageDisposition(MqsMessage handle, Disposition kind, String detail) {

    public static MessageDisposition ack(MqsMessage handle, String detail) {
        return new MessageDisposition(handle, Disposition.ACK, detail);
    }

    public static MessageDisposition ackDeferred(MqsMessage handle, String detail) {
        return new MessageDisposition(handle, Disposition.ACK_DEFERRED, detail);
    }

    public static MessageDisposition ackDlq(MqsMessage handle, String detail) {
        return new MessageDisposition(handle, Disposition.ACK_DLQ, detail);
    }

    public static MessageDisposition noAck(MqsMessage handle, String detail) {
        return new MessageDisposition(handle, Disposition.NO_ACK, detail);
    }
}
