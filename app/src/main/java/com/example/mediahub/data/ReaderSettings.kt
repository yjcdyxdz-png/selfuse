package com.example.mediahub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class ReaderSettings(
    val fontSize: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val theme: Int = 1,
    val pageMode: String = MODE_SCROLL,
    val night: Boolean = false,
) {
    companion object {
        const val MODE_SCROLL = "scroll"
        const val MODE_PAGE = "page"
        /** 背景/文字颜色预设 (ARGB) */
        val THEMES = listOf(
            0xFFFFFFFF to 0xFF222222,
            0xFFF5ECD7 to 0xFF3B3226,
            0xFFCCE8CF to 0xFF203020,
            0xFFE8E8E8 to 0xFF222222,
            0xFF1E2A38 to 0xFFC8D0D8,
        )
        val NIGHT = 0xFF121212 to 0xFF8A8A8A
    }
    fun colors(): Pair<Long, Long> = if (night) NIGHT else THEMES.getOrElse(theme) { THEMES[0] }
}

private val Context.readerStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

object ReaderPrefs {
    private val FONT = floatPreferencesKey("font")
    private val LINE = floatPreferencesKey("line")
    private val THEME = intPreferencesKey("theme")
    private val MODE = stringPreferencesKey("mode")
    private val NIGHT = booleanPreferencesKey("night")

    fun flow(ctx: Context): Flow<ReaderSettings> = ctx.readerStore.data.map { p ->
        ReaderSettings(p[FONT] ?: 18f, p[LINE] ?: 1.6f, p[THEME] ?: 1, p[MODE] ?: ReaderSettings.MODE_SCROLL, p[NIGHT] ?: false)
    }

    suspend fun save(ctx: Context, s: ReaderSettings) {
        ctx.readerStore.edit {
            it[FONT] = s.fontSize; it[LINE] = s.lineSpacing; it[THEME] = s.theme; it[MODE] = s.pageMode; it[NIGHT] = s.night
        }
    }

    suspend fun exportJson(ctx: Context): JSONObject = PrefsIO.fromPreferences(ctx.readerStore.data.first())
    suspend fun importJson(ctx: Context, o: JSONObject) { ctx.readerStore.edit { PrefsIO.applyToPreferences(it, o) } }
}
