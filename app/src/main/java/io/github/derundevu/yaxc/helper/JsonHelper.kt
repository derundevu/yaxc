package io.github.derundevu.yaxc.helper

import org.json.JSONArray
import org.json.JSONObject

class JsonHelper {
    companion object {
        fun makeObject(value: String) = sanitizeObject(JSONObject(value))

        fun makeArray(value: String) = sanitizeArray(JSONArray(value))

        fun getObject(value: JSONObject, key: String) =
            value.optJSONObject(key) ?: JSONObject()

        fun getArray(value: JSONObject, key: String) =
            value.optJSONArray(key) ?: JSONArray()

        fun mergeObjects(obj1: JSONObject, obj2: JSONObject): JSONObject {
            val result = JSONObject(obj1.toString())

            for (key in obj2.keys()) {
                val value2 = obj2[key]
                if (result.has(key)) {
                    val value1 = result[key]
                    when {
                        value1 is JSONObject && value2 is JSONObject -> {
                            result.put(key, mergeObjects(value1, value2))
                        }
                        value1 is JSONArray && value2 is JSONArray -> {
                            result.put(key, mergeArrays(value1, value2))
                        }
                        else -> result.put(key, value2)
                    }
                } else result.put(key, value2)
            }

            return result
        }

        fun mergeArrays(arr1: JSONArray, arr2: JSONArray, mergeKey: String = ""): JSONArray {
            val result = JSONArray()

            for (i in 0 until arr1.length()) result.put(arr1[i])

            for (i in 0 until arr2.length()) {
                val value2 = arr2[i]
                if (value2 is JSONObject && value2.has(mergeKey)) {
                    val keyValue = value2[mergeKey]
                    var merged = false
                    for (j in 0 until result.length()) {
                        val value1 = result[j]
                        if (value1 is JSONObject && value1.has(mergeKey) && value1[mergeKey] == keyValue) {
                            result.put(j, mergeObjects(value1, value2))
                            merged = true
                            break
                        }
                    }
                    if (!merged) result.put(value2)
                } else result.put(value2)
            }

            return result
        }

        private fun sanitizeObject(source: JSONObject): JSONObject {
            val sanitized = JSONObject()
            val keys = source.keys().asSequence().toList()
            keys.forEach { key ->
                sanitizeValue(source.opt(key))?.let { sanitized.put(key, it) }
            }
            return sanitized
        }

        private fun sanitizeArray(source: JSONArray): JSONArray {
            val sanitized = JSONArray()
            for (index in 0 until source.length()) {
                sanitizeValue(source.opt(index))?.let(sanitized::put)
            }
            return sanitized
        }

        private fun sanitizeValue(value: Any?): Any? {
            return when (value) {
                null, JSONObject.NULL -> null
                is JSONObject -> sanitizeObject(value)
                is JSONArray -> sanitizeArray(value)
                else -> value
            }
        }
    }
}
