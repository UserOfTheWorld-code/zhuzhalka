package com.v2ray.ang.handler

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Untrusted protocol input has its own parser: no raw-input exception may reach the logging JSON helper. */
internal object CommunityJson {
    fun parse(json: String, maxChars: Int): JsonElement? {
        if (json.length !in 1..maxChars) return null
        return runCatching {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                var nodes = 0
                fun value(depth: Int) {
                    require(depth <= 8 && ++nodes <= 1_024)
                    when (reader.peek()) {
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            while (reader.hasNext()) value(depth + 1)
                            reader.endArray()
                        }
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val keys = mutableSetOf<String>()
                            while (reader.hasNext()) {
                                require(keys.add(reader.nextName()))
                                value(depth + 1)
                            }
                            reader.endObject()
                        }
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> error("Unexpected JSON token")
                    }
                }
                value(0)
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            JsonParser.parseString(json)
        }.getOrNull()
    }
}
