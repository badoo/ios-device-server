package com.badoo.automation.deviceserver

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.InputStream

class JsonMapper {

    private val mapper = jacksonObjectMapper()
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
        .configure(JsonParser.Feature.ALLOW_NUMERIC_LEADING_ZEROS, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
        .configure(JsonParser.Feature.ALLOW_YAML_COMMENTS, true)!!

    fun <T> fromJson(string: String, clazz: Class<T>): T {
        return mapper.readValue(string, clazz)
    }

    fun <T> fromJson(stream: InputStream, clazz: Class<T>): T {
        return mapper.readValue(stream, clazz)
    }

    fun <T> fromJson(json: JsonNode, clazz: Class<T>): T {
        return mapper.treeToValue(json, clazz)
    }

    fun readTree(stream: InputStream): JsonNode {
        return mapper.readTree(stream) ?: throw RuntimeException("Failed to parse json")
    }

    inline fun <reified T> fromJson(string: String): T = fromJson(string, T::class.java)
    inline fun <reified T> fromJson(stream: InputStream): T = fromJson(stream, T::class.java)
    inline fun <reified T> fromJson(json: JsonNode): T = fromJson(json, T::class.java)

    fun <T> toJson(value: T): String {
        return mapper.writeValueAsString(value)
    }
}