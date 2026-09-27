package com.mqspg.common.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL {@code jsonb} ←→ Jackson {@link JsonNode} 映射。
 *
 * <p>使用目的：{@code cfg_datasource.pool_config}、{@code cfg_target.upsert_keys}、
 * {@code cfg_version.content}、{@code raw_message.payload} 均为 jsonb 列。
 *
 * <p>注意：写库时必须使用 {@link PGobject} 并显式声明类型为 {@code jsonb}，
 * 否则 PG 会以 {@code text} 接收并报类型不匹配。
 *
 * <p>实体上需配合 {@code @TableName(autoResultMap = true)}，
 * 否则 typeHandler 不会作用于 SELECT 结果映射。
 */
@MappedTypes(JsonNode.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<JsonNode> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(parameter.toString());
        ps.setObject(i, pg);
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private JsonNode parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new SQLException("无法解析 jsonb 列内容: " + e.getMessage(), e);
        }
    }
}
