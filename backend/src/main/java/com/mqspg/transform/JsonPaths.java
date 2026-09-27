package com.mqspg.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.mqspg.common.error.ErrorCode;
import com.mqspg.common.error.ProcessingException;
import com.mqspg.common.persistence.JsonbTypeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSONPath 求值，带编译缓存。
 *
 * <p>刻意**不**开启 {@link Option#SUPPRESS_EXCEPTIONS}：路径不存在与路径写错是两回事，
 * 前者应由调用方回落到默认值，后者应作为配置错误暴露出来。这里统一返回
 * {@code null} 表示「路径存在但取不到值」，由调用方决定后续处理。
 */
@Slf4j
@Component
public class JsonPaths {

    private static final Configuration CONF = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(JsonbTypeHandler.mapper()))
            .mappingProvider(new JacksonMappingProvider(JsonbTypeHandler.mapper()))
            .build();

    private final Map<String, JsonPath> cache = new ConcurrentHashMap<>();

    /**
     * 求值。路径非法时抛 {@link ProcessingException}（PATH_ERROR），
     * 路径合法但取不到值时返回 {@code null}。
     */
    public Object read(JsonNode document, String path) {
        if (document == null || path == null || path.isBlank()) {
            return null;
        }
        JsonPath compiled;
        try {
            compiled = cache.computeIfAbsent(path, JsonPath::compile);
        } catch (JsonPathException e) {
            throw new ProcessingException(ErrorCode.PATH_ERROR,
                    "JSONPath 表达式非法: " + path, e);
        }
        try {
            return compiled.read(document, CONF);
        } catch (JsonPathException e) {
            // 路径合法但文档中不存在 —— 属于「取不到值」，不是错误
            return null;
        }
    }

    /** 预编译，供配置发布时做语法校验。 */
    public void validate(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            cache.computeIfAbsent(path, JsonPath::compile);
        } catch (JsonPathException e) {
            throw new ProcessingException(ErrorCode.PATH_ERROR, "JSONPath 表达式非法: " + path, e);
        }
    }
}
