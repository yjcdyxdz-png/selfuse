package com.example.mediahub.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.mediahub.data.Library
import com.example.mediahub.data.RuleKind
import com.example.mediahub.data.ShelfEntry
import com.example.mediahub.ui.comic.ComicReaderScreen
import com.example.mediahub.ui.common.Hit
import com.example.mediahub.ui.detail.DetailScreen
import com.example.mediahub.ui.explore.ExploreScreen
import com.example.mediahub.ui.logs.LogScreen
import com.example.mediahub.ui.novel.NovelDetailScreen
import com.example.mediahub.ui.novel.NovelReaderScreen
import com.example.mediahub.ui.player.PlayerScreen
import com.example.mediahub.ui.rules.RuleImportScreen
import com.example.mediahub.ui.rules.RuleTestScreen
import com.example.mediahub.ui.search.SearchScreen
import com.example.mediahub.ui.shelf.ShelfScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("shelf", "书架", Icons.Default.Bookmarks),
    Tab("explore", "发现", Icons.Default.Explore),
    Tab("search", "搜索", Icons.Default.Search),
    Tab("rules", "规则", Icons.Default.Extension),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    val str = { s: String -> navArgument(s) { type = NavType.StringType } }
    val int = { s: String -> navArgument(s) { type = NavType.IntType } }
    val openHit = { h: Hit -> nav.navigate(
        if (h.rule.kind == RuleKind.LEGADO_BOOK) Routes.novel(h.rule.id, h.id) else Routes.detail(h.rule.id, h.id)) }

    Scaffold(bottomBar = {
        if (tabs.any { it.route == current }) NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(selected = current == t.route, onClick = {
                    nav.navigate(t.route) { popUpTo("shelf") { saveState = true }; launchSingleTop = true; restoreState = true }
                }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = "shelf", modifier = Modifier.padding(pad)) {
            composable("shelf") {
                ShelfScreen(onResume = { resume(nav, it) }, onDetail = { e ->
                    nav.navigate(if (e.item.contentType == "novel") Routes.novel(e.item.ruleId, e.item.itemId)
                        else Routes.detail(e.item.ruleId, e.item.itemId))
                })
            }
            composable("explore") { ExploreScreen(onOpen = openHit) }
            composable("search") { SearchScreen(onOpen = openHit) }
            composable("rules") {
                RuleImportScreen(onTest = { r -> nav.navigate(Routes.test(r.id)) }, onLogs = { nav.navigate("logs") })
            }
            composable("logs") { LogScreen(onBack = { nav.popBackStack() }) }
            composable("ruletest/{rule}", arguments = listOf(str("rule"))) { e ->
                RuleTestScreen(e.arguments!!.getString("rule")!!, onBack = { nav.popBackStack() }, onLogs = { nav.navigate("logs") })
            }
            composable("detail/{rule}/{sid}", arguments = listOf(str("rule"), str("sid"))) { e ->
                val rule = e.arguments!!.getString("rule")!!; val sid = e.arguments!!.getString("sid")!!
                DetailScreen(rule, sid, onBack = { nav.popBackStack() },
                    onPlay = { i -> nav.navigate(Routes.player(rule, sid, i)) },
                    onRead = { g, p -> nav.navigate(Routes.comic(rule, sid, g, p)) })
            }
            composable("player/{rule}/{sid}/{idx}", arguments = listOf(str("rule"), str("sid"), int("idx"))) { e ->
                val a = e.arguments!!
                PlayerScreen(a.getString("rule")!!, a.getString("sid")!!, a.getInt("idx"), onBack = { nav.popBackStack() })
            }
            composable("comic/{rule}/{sid}/{page}?group={group}", arguments = listOf(str("rule"), str("sid"), int("page"),
                navArgument("group") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                val a = e.arguments!!
                ComicReaderScreen(a.getString("rule")!!, a.getString("sid")!!, a.getString("group"), a.getInt("page"),
                    onBack = { nav.popBackStack() })
            }
            composable("novel/{rule}/{url}", arguments = listOf(str("rule"), str("url"))) { e ->
                val rule = e.arguments!!.getString("rule")!!; val url = e.arguments!!.getString("url")!!
                NovelDetailScreen(rule, url, onBack = { nav.popBackStack() },
                    onRead = { i -> nav.navigate(Routes.novelRead(rule, url, i)) })
            }
            composable("novelread/{rule}/{url}/{idx}", arguments = listOf(str("rule"), str("url"), int("idx"))) { e ->
                val a = e.arguments!!
                NovelReaderScreen(a.getString("rule")!!, a.getString("url")!!, a.getInt("idx"), onBack = { nav.popBackStack() })
            }
        }
    }
}

/** 从书架继续：有进度直接进入播放/阅读，否则进详情 */
private fun resume(nav: NavHostController, e: ShelfEntry) {
    val i = e.item; val p = e.progress
    nav.navigate(when {
        i.contentType == "novel" -> if (p != null) Routes.novelRead(i.ruleId, i.itemId, p.chapterIndex) else Routes.novel(i.ruleId, i.itemId)
        p == null -> Routes.detail(i.ruleId, i.itemId)
        i.contentType == "comic" -> Routes.comic(i.ruleId, i.itemId, Library.groupOf(p.extra), p.position.toInt())
        else -> Routes.player(i.ruleId, i.itemId, p.chapterIndex)
    })
}

object Routes {
    private fun e(s: String) = Uri.encode(s)
    fun detail(rule: String, sid: String) = "detail/${e(rule)}/${e(sid)}"
    fun player(rule: String, sid: String, i: Int) = "player/${e(rule)}/${e(sid)}/$i"
    fun comic(rule: String, sid: String, group: String?, page: Int) =
        "comic/${e(rule)}/${e(sid)}/$page" + (group?.let { "?group=${e(it)}" } ?: "")
    fun novel(rule: String, url: String) = "novel/${e(rule)}/${e(url)}"
    fun novelRead(rule: String, url: String, i: Int) = "novelread/${e(rule)}/${e(url)}/$i"
    fun test(rule: String) = "ruletest/${e(rule)}"
}
