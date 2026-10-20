package com.mqspg.writer.sql;

import java.util.List;

/**
 * 已编译的 MERGE 语句。
 *
 * @param sql      带 {@code ?} 占位符与显式 cast 的 SQL
 * @param params   与占位符顺序一一对应的参数
 * @param keyCount Upsert Key 的列数（用于解析 RETURNING 结果）
 */
public record CompiledMerge(String sql, List<Object> params, int keyCount) {

    public CompiledMerge {
        params = List.copyOf(params);
    }

    /**
     * 参数数组，供 {@code JdbcTemplate.query(String, ResultSetExtractor, Object...)} 使用。
     *
     * <p><b>为什么不能直接传 {@code params}</b>：那个重载是 varargs，
     * 而 {@code List} 重载（{@code query(String, ResultSetExtractor, List)}）的语义不同 ——
     * 它把整个 List 当**一个**参数绑定，PG 会报 SQLSTATE 07006
     * （restricted data type attribute violation）。J4 踩过一次。
     *
     * <p>这里仍要返回新数组：调用方每次执行都需要一个独立的 {@code Object[]}，
     * 而 {@code List.copyOf} 的结果无法安全展开成数组共享。
     * 省下的是「每行一个 ArrayList」（见 {@code KeyTuple#ofNormalized(Object[])}），
     * 不是这一处。
     */
    public Object[] paramsArray() {
        return params.toArray();
    }
}
