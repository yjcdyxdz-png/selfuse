package com.example.mediahub.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import coil.size.Size
import coil.transform.Transformation
import com.example.mediahub.engine.JsModuleEngine
import com.example.mediahub.util.AppLog
import kotlinx.serialization.json.*

/**
 * 调用模块可选的 transformImage(info)，info = {url, index, width, height, chapter}。
 * 返回 null/false 不处理；支持两种格式：
 *  1) 网格：{rows, cols, order:[...], direction?}  输出第 i 块取原图第 order[i] 块（默认行优先，"vertical" 为列优先）
 *  2) 逐块：{width, height, pieces:[{sx,sy,sw,sh,dx,dy,dw?,dh?}]}
 */
class TileTransformation(
    private val engine: JsModuleEngine,
    private val url: String,
    private val index: Int,
    private val chapter: JsonElement,
) : Transformation {
    override val cacheKey = "tile:${engine.sourceId}:$url"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val info = buildJsonObject {
            put("url", url); put("index", index)
            put("width", input.width); put("height", input.height)
            put("chapter", chapter)
        }
        val r = runCatching { engine.transformImage(info) }
            .onFailure { AppLog.e("Comic", "transformImage 失败", it) }.getOrNull()
        val o = r as? JsonObject ?: return input
        return runCatching {
            when {
                o["pieces"] is JsonArray -> pieces(input, o)
                o["order"] is JsonArray -> grid(input, o)
                else -> input
            }
        }.onFailure { AppLog.e("Comic", "切块重排失败", it) }.getOrDefault(input)
    }

    private fun grid(src: Bitmap, o: JsonObject): Bitmap {
        val rows = o.int("rows") ?: 1; val cols = o.int("cols") ?: 1
        val order = (o["order"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        if (order.isEmpty() || rows * cols <= 1) return src
        val vertical = (o["direction"] as? JsonPrimitive)?.contentOrNull == "vertical"
        val out = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        fun xs(i: Int) = src.width * i / cols
        fun ys(i: Int) = src.height * i / rows
        fun rect(n: Int): Rect {
            val r = if (vertical) n % rows else n / cols
            val col = if (vertical) n / rows else n % cols
            return Rect(xs(col), ys(r), xs(col + 1), ys(r + 1))
        }
        for (dst in 0 until rows * cols) c.drawBitmap(src, rect(order.getOrNull(dst) ?: dst), rect(dst), null)
        return out
    }

    private fun pieces(src: Bitmap, o: JsonObject): Bitmap {
        val w = o.int("width") ?: src.width; val h = o.int("height") ?: src.height
        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        (o["pieces"] as JsonArray).forEach { el ->
            val p = el as? JsonObject ?: return@forEach
            val sx = p.int("sx") ?: 0; val sy = p.int("sy") ?: 0
            val sw = p.int("sw") ?: 0; val sh = p.int("sh") ?: 0
            val dx = p.int("dx") ?: 0; val dy = p.int("dy") ?: 0
            val dw = p.int("dw") ?: sw; val dh = p.int("dh") ?: sh
            if (sw > 0 && sh > 0) c.drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(dx, dy, dx + dw, dy + dh), null)
        }
        return out
    }

    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
}
