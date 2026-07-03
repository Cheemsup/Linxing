package org.linxing.linxing_agent.agent.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * MyBatis TypeHandler：PostgreSQL JSONB（数组）↔ List<Map<String, Object>>
 * 用于处理 chunks.node_metadata 这类存储为 JSON 数组的字段
 */
@MappedTypes(List.class)
public class JsonListTypeHandler extends BaseTypeHandler<List<Map<String, Object>>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JavaType LIST_TYPE = OBJECT_MAPPER.getTypeFactory()
            .constructCollectionType(List.class, Map.class);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    List<Map<String, Object>> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setString(i, OBJECT_MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("JSON序列化失败", e);
        }
    }

    @Override
    public List<Map<String, Object>> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJsonArray(rs.getString(columnName));
    }

    @Override
    public List<Map<String, Object>> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJsonArray(rs.getString(columnIndex));
    }

    @Override
    public List<Map<String, Object>> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJsonArray(cs.getString(columnIndex));
    }

    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}