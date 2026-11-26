package com.example.mediahub.data

import android.content.SharedPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * DataStore / SharedPreferences 与 JSON 互转（备份用）。
 * 每个值带类型：{"key": {"t": "i", "v": 1}}，恢复时按原类型写回。
 */
internal object PrefsIO {
    private fun typed(v: Any?): JSONObject? = when (v) {
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Double -> JSONObject().put("t", "d").put("v", v)
        is String -> JSONObject().put("t", "s").put("v", v)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
        else -> null
    }

    private fun strings(a: JSONArray): Set<String> = (0 until a.length()).map { a.getString(it) }.toSet()

    fun fromPreferences(p: Preferences): JSONObject {
        val o = JSONObject()
        p.asMap().forEach { (k, v) -> typed(v)?.let { o.put(k.name, it) } }
        return o
    }

    fun applyToPreferences(p: MutablePreferences, o: JSONObject) {
        o.keys().forEach { k ->
            val e = o.optJSONObject(k) ?: return@forEach
            runCatching {
                when (e.optString("t")) {
                    "b" -> p[booleanPreferencesKey(k)] = e.getBoolean("v")
                    "i" -> p[intPreferencesKey(k)] = e.getInt("v")
                    "l" -> p[longPreferencesKey(k)] = e.getLong("v")
                    "f" -> p[floatPreferencesKey(k)] = e.getDouble("v").toFloat()
                    "d" -> p[doublePreferencesKey(k)] = e.getDouble("v")
                    "s" -> p[stringPreferencesKey(k)] = e.getString("v")
                    "ss" -> p[stringSetPreferencesKey(k)] = strings(e.getJSONArray("v"))
                }
            }
        }
    }

    fun fromShared(sp: SharedPreferences): JSONObject {
        val o = JSONObject()
        sp.all.forEach { (k, v) -> typed(v)?.let { o.put(k, it) } }
        return o
    }

    /** 合并写入（不删除备份里没有的键），同步提交，方便随后重新读取 */
    fun applyToShared(sp: SharedPreferences, o: JSONObject) {
        val ed = sp.edit()
        o.keys().forEach { k ->
            val e = o.optJSONObject(k) ?: return@forEach
            runCatching {
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "d" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "ss" -> ed.putStringSet(k, strings(e.getJSONArray("v")))
                }
            }
        }
        ed.commit()
    }
}
