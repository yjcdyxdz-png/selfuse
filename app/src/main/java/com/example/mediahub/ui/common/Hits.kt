package com.example.mediahub.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.engine.HostBridge
import com.example.mediahub.engine.MediaItem
import com.example.mediahub.novel.NovelCache
import com.example.mediahub.novel.SearchBook

/** 搜索/发现结果的统一表示。id：JS 模块为 sourceId，书源为 bookUrl */
data class Hit(
    val rule: RuleEntity,
    val id: String,
    val title: String,
    val cover: String = "",
    val desc: String = "",
    val author: String = "",
)

fun MediaItem.toHit(rule: RuleEntity) =
    Hit(rule, sourceId.ifBlank { url }.ifBlank { id }.ifBlank { title }, title, cover, description)

fun SearchBook.toHit(rule: RuleEntity): Hit {
    NovelCache.hits[bookUrl] = this
    val d = listOf(kind, lastChapter).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { intro }
    return Hit(rule, bookUrl, name, coverUrl, d, author)
}

@Composable
fun Cover(url: String, referer: String, modifier: Modifier) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url.ifBlank { null })
            .addHeader("User-Agent", HostBridge.DEFAULT_UA)
            .apply { if (referer.isNotBlank()) addHeader("Referer", referer) }.crossfade(true).build(),
        contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(6.dp)))
}

@Composable
fun HitRow(hit: Hit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cover(hit.cover, hit.rule.baseUrl, Modifier.width(60.dp).height(82.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(hit.author, hit.rule.name).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            if (hit.desc.isNotBlank()) Text(hit.desc, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun HitCard(hit: Hit, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Cover(hit.cover, hit.rule.baseUrl, Modifier.fillMaxWidth().aspectRatio(0.72f))
        Text(hit.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

fun typeLabel(t: String) = when (t) { "comic" -> "漫画"; "novel" -> "小说"; else -> "视频" }
