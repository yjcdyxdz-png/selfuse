package com.example.mediahub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/** 视频播放设置 */
data class VideoPrefs(
    /** 默认倍速，在播放器里改了也会记住 */
    val speed: Float = 1f,
    /** 长按时的临时倍速，1 表示关闭长按倍速 */
    val longPressSpeed: Float = 2f,
    /** 竖屏视频（短剧）上下滑切换上一集/下一集 */
    val swipeEpisodes: Boolean = true,
)

/** App 全局设置（阅读器设置在 ReaderPrefs 里） */
object AppSettings {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private val THEME = intPreferencesKey("theme_mode")
    private val V_SPEED = floatPreferencesKey("video_speed")
    private val V_LONG = floatPreferencesKey("video_long_press_speed")
    private val V_SWIPE = booleanPreferencesKey("video_swipe_episodes")
    private val SHELF_SORT = intPreferencesKey("shelf_sort")
    private val SHELF_AUTO = booleanPreferencesKey("shelf_auto_check")
    private val HEALTH_KW = stringPreferencesKey("health_keyword")

    fun themeMode(ctx: Context): Flow<Int> = ctx.appStore.data.map { it[THEME] ?: THEME_SYSTEM }

    suspend fun setThemeMode(ctx: Context, mode: Int) {
        ctx.appStore.edit { it[THEME] = mode }
    }

    fun videoPrefs(ctx: Context): Flow<VideoPrefs> = ctx.appStore.data.map {
        VideoPrefs(
            speed = it[V_SPEED] ?: 1f,
            longPressSpeed = it[V_LONG] ?: 2f,
            swipeEpisodes = it[V_SWIPE] ?: true,
        )
    }

    suspend fun setVideoSpeed(ctx: Context, v: Float) { ctx.appStore.edit { it[V_SPEED] = v } }
    suspend fun setLongPressSpeed(ctx: Context, v: Float) { ctx.appStore.edit { it[V_LONG] = v } }
    suspend fun setSwipeEpisodes(ctx: Context, v: Boolean) { ctx.appStore.edit { it[V_SWIPE] = v } }

    /** 书架排序：0 最近阅读，1 最近加入，2 名称，3 有更新的优先 */
    fun shelfSort(ctx: Context): Flow<Int> = ctx.appStore.data.map { it[SHELF_SORT] ?: 0 }
    suspend fun setShelfSort(ctx: Context, v: Int) { ctx.appStore.edit { it[SHELF_SORT] = v } }

    /** 打开书架时自动检查更新（距上次超过 30 分钟才检查） */
    fun shelfAutoCheck(ctx: Context): Flow<Boolean> = ctx.appStore.data.map { it[SHELF_AUTO] ?: true }
    suspend fun setShelfAutoCheck(ctx: Context, v: Boolean) { ctx.appStore.edit { it[SHELF_AUTO] = v } }

    /** 批量检测规则时，没有分类/发现的源用这个关键词搜索 */
    fun healthKeyword(ctx: Context): Flow<String> = ctx.appStore.data.map { it[HEALTH_KW] ?: "我" }
    suspend fun setHealthKeyword(ctx: Context, v: String) { ctx.appStore.edit { it[HEALTH_KW] = v } }
}
