package com.hospital.core.clinical.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

/**
 * R-51: {@link JsonbTypeHandler} 此前零测试。这里用 Mockito mock ResultSet / PreparedStatement,
 * 固化读写的实际契约(以当前实现行为为准)。
 *
 * <p>已知风险(不在本用例修复,仅固化):非法 JSON 走"静默降级"——
 * {@code jsonParse} 捕获 {@code JsonProcessingException} 后把原始字符串原样返回,
 * 调用方拿到的将不是 Map/List 而是 String。后续应收紧为抛 {@code SQLException} 让数据损坏显式暴露。
 */
class JsonbTypeHandlerTest {

    private final JsonbTypeHandler handler = new JsonbTypeHandler();

    private static ResultSet rsReturning(String column, String value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(column)).thenReturn(value);
        return rs;
    }

    @Nested
    @DisplayName("读:getNullableResult")
    class Read {

        @Test
        @DisplayName("R-51 合法 JSON 对象 → Map")
        void objectJson_returnsMap() throws Exception {
            Object result = handler.getNullableResult(
                    rsReturning("physical_exam", "{\"heart\":\"normal\"}"), "physical_exam");

            assertThat(result).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) result).get("heart")).isEqualTo("normal");
        }

        @Test
        @DisplayName("R-51 列值为 null → null")
        void nullValue_returnsNull() throws Exception {
            assertThat(handler.getNullableResult(rsReturning("col", null), "col")).isNull();
        }

        @Test
        @DisplayName("R-51 空串 → null")
        void emptyString_returnsNull() throws Exception {
            assertThat(handler.getNullableResult(rsReturning("col", ""), "col")).isNull();
        }

        @Test
        @DisplayName("R-51 \"null\"(JSON 字面量)→ null")
        void jsonLiteralNull_returnsNull() throws Exception {
            assertThat(handler.getNullableResult(rsReturning("col", "null"), "col")).isNull();
        }

        @Test
        @DisplayName("R-51 \"{}\" → 空 Map")
        void emptyObject_returnsEmptyMap() throws Exception {
            Object result = handler.getNullableResult(rsReturning("col", "{}"), "col");

            assertThat(result).isInstanceOf(Map.class);
            assertThat((Map<?, ?>) result).isEmpty();
        }

        @Test
        @DisplayName("R-51 \"[]\" → 空 List")
        void emptyArray_returnsEmptyList() throws Exception {
            Object result = handler.getNullableResult(rsReturning("col", "[]"), "col");

            assertThat(result).isInstanceOf(List.class);
            assertThat((List<?>) result).isEmpty();
        }

        @Test
        @DisplayName("R-51 非法 JSON(如 \"{\")→ 静默降级,原样返回字符串(已知风险)")
        void invalidJson_returnsRawString() throws Exception {
            // 契约固化:jsonParse 捕获 JsonProcessingException 后 return json(原始串),不抛、不置 null。
            // 后果:结构损坏的 JSONB 会以 String 形式透给 Map/List 字段,类型契约被打破。
            // 结论:这是"静默降级"风险,后续应改为 throw new SQLException("Invalid JSONB", e)。
            Object result = handler.getNullableResult(rsReturning("col", "{"), "col");

            assertThat(result).isEqualTo("{");
        }

        @Test
        @DisplayName("R-51 按 columnIndex 读取同样走 JSON 解析")
        void byIndex_parsesJson() throws Exception {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(3)).thenReturn("{\"a\":1}");

            Object result = handler.getNullableResult(rs, 3);

            assertThat(result).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) result).get("a")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("写:setNonNullParameter")
    class Write {

        @Test
        @DisplayName("R-51 写入带 jsonb 类型的 PGobject")
        void wrapsInJsonbPGobject() throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);

            handler.setNonNullParameter(ps, 1, Map.of("k", "v"), JdbcType.OTHER);

            ArgumentCaptor<Integer> indexCaptor = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(ps).setObject(indexCaptor.capture(), valueCaptor.capture());

            assertThat(indexCaptor.getValue()).isEqualTo(1);
            assertThat(valueCaptor.getValue()).isInstanceOf(PGobject.class);

            PGobject pg = (PGobject) valueCaptor.getValue();
            assertThat(pg.getType()).isEqualTo("jsonb");
            assertThat(pg.getValue()).isEqualTo("{\"k\":\"v\"}");
        }
    }
}
