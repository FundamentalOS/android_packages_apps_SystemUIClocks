/*
 * Copyright (C) 2026 FundamentalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.systemui.clocks

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import kotlin.reflect.KClass

/**
 * Polymorphic Gson adapter: picks the concrete subclass of [baseClass] from the value of the
 * [typeProp] field (e.g. `"type": "analog-hand"`).
 */
class BaseTypeAdapterFactory<T : Any>(
    private val baseClass: KClass<T>,
    private val typeProp: String = "type",
) : TypeAdapterFactory {
    private val subtypeMap = mutableMapOf<String, KClass<out T>>()

    fun registerSubclass(subclass: KClass<out T>, key: String? = null) {
        val k = key ?: subclass.simpleName ?: throw IllegalStateException("No key specified for $subclass")
        if (subtypeMap.containsKey(k)) throw IllegalStateException("Subtype already exists at key: $k")
        subtypeMap[k] = subclass
    }

    override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? {
        if (type.rawType != baseClass.java) return null

        val elementAdapter = gson.getAdapter(JsonElement::class.java)
        val readMap = mutableMapOf<String, TypeAdapter<out T>>()
        val writeMap = mutableMapOf<KClass<out T>, Pair<String, TypeAdapter<out T>>>()
        for ((key, subclass) in subtypeMap) {
            val delegate = gson.getDelegateAdapter(this, TypeToken.get(subclass.java))
            readMap[key] = delegate
            writeMap[subclass] = key to delegate
        }

        return object : TypeAdapter<R>() {
            override fun read(reader: JsonReader): R {
                val element = elementAdapter.read(reader)
                val obj = element.asJsonObject
                val typeElement =
                    obj.remove(typeProp)
                        ?: throw JsonParseException(
                            "Failed to deserialize ${baseClass.simpleName}; " +
                                "Missing type definition field $typeProp."
                        )
                val key = typeElement.asString
                val adapter =
                    readMap[key]
                        ?: throw JsonParseException(
                            "Failed to deserialize ${baseClass.simpleName}; " +
                                "No subtype for specified type key: $key"
                        )
                @Suppress("UNCHECKED_CAST")
                return adapter.fromJsonTree(element) as R
            }

            override fun write(writer: JsonWriter, value: R) {
                @Suppress("UNCHECKED_CAST")
                val cls = (value as Any)::class as KClass<out T>
                val (key, adapter) =
                    writeMap[cls]
                        ?: throw JsonParseException(
                            "Failed to serialize ${cls.simpleName}; " +
                                "Subclass was not registered and cannot be serialized."
                        )
                @Suppress("UNCHECKED_CAST")
                val obj = (adapter as TypeAdapter<Any>).toJsonTree(value).asJsonObject
                obj.add(typeProp, JsonPrimitive(key))
                elementAdapter.write(writer, obj)
            }
        }
    }
}
