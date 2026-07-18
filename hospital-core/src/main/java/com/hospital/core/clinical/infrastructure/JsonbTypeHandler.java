package com.hospital.core.clinical.infrastructure;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSONB type handler for PostgreSQL — wraps JSON strings in a PGobject("jsonb")
 * for writes, and deserializes via Jackson for reads.
 */
@MappedTypes({Object.class})
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<Object> {
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Class<?> javaType;

    public JsonbTypeHandler() {
        this.javaType = Object.class;
    }

    public JsonbTypeHandler(Class<?> clazz) {
        this.javaType = clazz;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    Object parameter, JdbcType jdbcType) throws SQLException {
        try {
            String json = MAPPER.writeValueAsString(parameter);
            PGobject pgObj = new PGobject();
            pgObj.setType("jsonb");
            pgObj.setValue(json);
            ps.setObject(i, pgObj);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize to JSONB", e);
        }
    }

    @Override
    public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return jsonParse(json);
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return jsonParse(json);
    }

    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return jsonParse(json);
    }

    private Object jsonParse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
