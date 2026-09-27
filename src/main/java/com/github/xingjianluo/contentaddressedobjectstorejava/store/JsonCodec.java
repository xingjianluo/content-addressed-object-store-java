package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 共享 Jackson ObjectMapper（线程安全配置一次）。 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private JsonCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }

    public static <T> T read(byte[] data, Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (Exception e) {
            throw new IllegalStateException("json decode failed", e);
        }
    }
}
