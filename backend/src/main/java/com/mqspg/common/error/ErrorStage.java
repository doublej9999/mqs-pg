package com.mqspg.common.error;

/** 错误发生的处理阶段，对应 tech-design.md §8.1 的处理链。 */
public enum ErrorStage {
    RECEIVE,
    PARSE,
    JSLT,
    PATH,
    CONVERT,
    EXPRESSION,
    VALIDATE,
    WRITE,
    RETRY
}
