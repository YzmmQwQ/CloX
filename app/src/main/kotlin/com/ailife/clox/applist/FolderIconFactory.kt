package com.ailife.clox.applist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.ailife.clox.icon.IconResolver
import kotlin.math.ceil

/**
 * Builds a folder's composite icon: a translucent tile (circle or rounded-rectangle, matching the
 * current app-list style) with a 2x2 (<=4 members) or 3x3 (>=5 members) grid of member app icons.
 *
 * There is no existing multi-drawable compositor in the codebase, so this is net-new. No glyphs or
 * hand-written vector paths are used — member icons come straight from [IconResolver.resolve].
 */
object FolderIconFactory {

    enum class Shape { CIRCLE, ROUNDED_RECT }

    /** Circle for the circular styles, rounded-rect for the rest (mirrors the per-style cache choice). */
    fun shapeForStyle(style: String): Shape = when (style) {
        AppListPrefs.STYLE_WATERFALL,
        AppListPrefs.STYLE_HONEYCOMB,
        AppListPrefs.STYLE_GRAVITY -> Shape.CIRCLE
        else -> Shape.ROUNDED_RECT
    }

    /**
     * @param memberKeys up to 9 "pkg/activity" keys (already capped by the caller).
     * @param sizePx square output size in pixels.
     */
    fun build(ctx: Context, memberKeys: List<String>, sizePx: Int, shape: Shape): Drawable {
        val size = sizePx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ── Background tile ──────────────────────────────────────────────────
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tileBackgroundColor(ctx) }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        when (shape) {
            Shape.CIRCLE -> canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
            Shape.ROUNDED_RECT -> {
                val r = size * 0.22f
                canvas.drawRoundRect(rect, r, r, bgPaint)
            }
        }

        // ── Member icon grid ─────────────────────────────────────────────────
        val members = memberKeys.take(AppListPrefs.MAX_FOLDER_MEMBERS)
        val n = members.size
        if (n > 0) {
            val cols = if (n <= 4) 2 else 3
            val pad = size * 0.15f
            val gap = size * 0.04f
            val gridArea = size - 2 * pad
            val cell = (gridArea - (cols - 1) * gap) / cols
            val usedRows = ceil(n / cols.toFloat()).toInt()
            // Vertically centre the used rows within the cols x cols grid.
            val rowOffset = (cols - usedRows) * (cell + gap) / 2f

            for (i in 0 until n) {
                val row = i / cols
                val itemsThisRow = if (row == usedRows - 1) n - row * cols else cols
                // Horizontally centre a partial last row.
                val colOffset = (cols - itemsThisRow) * (cell + gap) / 2f
                val col = i % cols
                val left = pad + colOffset + col * (cell + gap)
                val top = pad + rowOffset + row * (cell + gap)
                val d = resolveMember(ctx, members[i]) ?: continue
                d.setBounds(left.toInt(), top.toInt(), (left + cell).toInt(), (top + cell).toInt())
                d.draw(canvas)
            }
        }

        val base: Drawable = BitmapDrawable(null, bmp).apply { setFilterBitmap(true) }
        // Clip to a hard circle for circular styles so corner icons don't poke out.
        return if (shape == Shape.CIRCLE) base.toCircle(size) else base
    }

    private fun resolveMember(ctx: Context, key: String): Drawable? {
        val slash = key.indexOf('/')
        if (slash <= 0) return null
        val pkg = key.substring(0, slash)
        val activity = key.substring(slash + 1)
        return try { IconResolver.resolve(ctx, pkg, activity) } catch (_: Exception) { null }
    }

    /** Translucent surface-variant colour so the tile reads as a container in light and dark. */
    private fun tileBackgroundColor(ctx: Context): Int {
        val tv = TypedValue()
        val resolved = ctx.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceVariant, tv, true
        )
        val base = if (resolved && tv.data != 0) tv.data else Color.rgb(120, 120, 120)
        return Color.argb(150, Color.red(base), Color.green(base), Color.blue(base))
    }
}
