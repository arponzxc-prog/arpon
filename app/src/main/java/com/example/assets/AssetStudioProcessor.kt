package com.example.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

enum class ImageExportFormat(val ext: String, val label: String) {
    PNG("png", "PNG (Lossless)"),
    JPEG("jpg", "JPEG (Compressed)"),
    WEBP("webp", "WEBP (Modern)")
}

enum class IconShapeOption(val label: String) {
    CIRCLE("Circle"),
    SQUIRCLE("Rounded"),
    SQUARE("Square")
}

data class ImportedAssetInfo(
    val fileName: String,
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val isSvg: Boolean = false,
    val rawSvgText: String? = null
)

data class ProjectAssetEntry(
    val name: String,
    val relativePath: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val extension: String
)

object AssetStudioProcessor {

    const val MAX_IMPORT_BYTES = 15L * 1024L * 1024L // 15 MB safety cap

    fun decodeImageUri(context: Context, uri: Uri, displayName: String): Result<ImportedAssetInfo> {
        return try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalStateException("Could not open selected image stream."))

            if (rawBytes.size > MAX_IMPORT_BYTES) {
                return Result.failure(IllegalArgumentException("Image exceeds maximum 15 MB limit."))
            }

            val textHead = rawBytes.take(256).toByteArray().decodeToString()
            if (displayName.endsWith(".svg", true) || textHead.contains("<svg", ignoreCase = true)) {
                val svgStr = rawBytes.decodeToString()
                val rendered = renderBasicSvgToBitmap(svgStr, 512, 512)
                return Result.success(
                    ImportedAssetInfo(
                        fileName = displayName,
                        bitmap = rendered,
                        width = rendered.width,
                        height = rendered.height,
                        byteSize = rawBytes.size.toLong(),
                        isSvg = true,
                        rawSvgText = svgStr
                    )
                )
            }

            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return Result.failure(IllegalArgumentException("Unsupported or corrupted image file."))

            Result.success(
                ImportedAssetInfo(
                    fileName = displayName,
                    bitmap = bmp,
                    width = bmp.width,
                    height = bmp.height,
                    byteSize = rawBytes.size.toLong(),
                    isSvg = false
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lightweight local SVG previewer supporting viewBox dimensions, <rect>, <circle>, and basic colors.
     */
    fun renderBasicSvgToBitmap(svgText: String, width: Int = 512, height: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val viewBoxMatch = Regex("""viewBox\s*=\s*["']\s*[\d.-]+\s+[\d.-]+\s+([\d.]+)\s+([\d.]+)["']""").find(svgText)
        val vbW = viewBoxMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceAtLeast(1f) ?: 100f
        val vbH = viewBoxMatch?.groupValues?.get(2)?.toFloatOrNull()?.coerceAtLeast(1f) ?: 100f
        val scaleX = width / vbW
        val scaleY = height / vbH

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Render <rect> elements
        Regex("""<rect\b([^>]+)>""").findAll(svgText).forEach { m ->
            val attrs = m.groupValues[1]
            val x = extractAttrFloat(attrs, "x") * scaleX
            val y = extractAttrFloat(attrs, "y") * scaleY
            val w = extractAttrFloat(attrs, "width", vbW) * scaleX
            val h = extractAttrFloat(attrs, "height", vbH) * scaleY
            val rx = extractAttrFloat(attrs, "rx") * scaleX
            paint.color = parseSvgColor(extractAttrStr(attrs, "fill") ?: "#38BDF8")
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), rx, rx, paint)
        }

        // Render <circle> elements
        Regex("""<circle\b([^>]+)>""").findAll(svgText).forEach { m ->
            val attrs = m.groupValues[1]
            val cx = extractAttrFloat(attrs, "cx", vbW / 2f) * scaleX
            val cy = extractAttrFloat(attrs, "cy", vbH / 2f) * scaleY
            val r = extractAttrFloat(attrs, "r", vbW / 4f) * ((scaleX + scaleY) / 2f)
            paint.color = parseSvgColor(extractAttrStr(attrs, "fill") ?: "#A855F7")
            canvas.drawCircle(cx, cy, r, paint)
        }

        // Fallback badge text if SVG only has complex paths
        if (!svgText.contains("<rect") && !svgText.contains("<circle")) {
            paint.color = Color.parseColor("#1E293B")
            canvas.drawRoundRect(RectF(16f, 16f, width - 16f, height - 16f), 48f, 48f, paint)
            paint.color = Color.parseColor("#00D2FF")
            paint.textSize = width * 0.22f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("SVG", width / 2f, height / 2f + paint.textSize * 0.35f, paint)
        }

        return bmp
    }

    private fun extractAttrStr(attrs: String, name: String): String? {
        val regex = Regex("""\b$name\s*=\s*["']([^"']+)["']""")
        return regex.find(attrs)?.groupValues?.get(1)
    }

    private fun extractAttrFloat(attrs: String, name: String, default: Float = 0f): Float {
        return extractAttrStr(attrs, name)?.replace("px", "")?.toFloatOrNull() ?: default
    }

    private fun parseSvgColor(raw: String): Int {
        return try {
            if (raw.equals("none", true)) Color.TRANSPARENT else Color.parseColor(raw)
        } catch (e: Exception) {
            Color.parseColor("#38BDF8")
        }
    }

    fun resizeBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val w = targetWidth.coerceIn(16, 4096)
        val h = targetHeight.coerceIn(16, 4096)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    fun cropCenterAspect(source: Bitmap, ratioW: Int, ratioH: Int): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val targetRatio = ratioW.toFloat() / ratioH.toFloat()
        val currentRatio = srcW.toFloat() / srcH.toFloat()

        return if (currentRatio > targetRatio) {
            val newW = (srcH * targetRatio).toInt().coerceIn(1, srcW)
            val startX = ((srcW - newW) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, startX, 0, newW, srcH)
        } else {
            val newH = (srcW / targetRatio).toInt().coerceIn(1, srcH)
            val startY = ((srcH - newH) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(source, 0, startY, srcW, newH)
        }
    }

    fun encodeBitmap(bitmap: Bitmap, format: ImageExportFormat, quality: Int = 92): ByteArray {
        val bos = ByteArrayOutputStream()
        val compressFmt = when (format) {
            ImageExportFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageExportFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
        }
        bitmap.compress(compressFmt, quality.coerceIn(10, 100), bos)
        return bos.toByteArray()
    }

    /**
     * Generates a high-res launcher icon bitmap with custom background color, shape mask, foreground logo or monogram.
     */
    fun generateLauncherIconBitmap(
        sizePx: Int,
        backgroundColorInt: Int,
        foregroundColorInt: Int,
        shape: IconShapeOption,
        monogramText: String,
        foregroundBitmap: Bitmap? = null,
        paddingRatio: Float = 0.18f
    ): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColorInt }
        val radius = when (shape) {
            IconShapeOption.CIRCLE -> sizePx / 2f
            IconShapeOption.SQUIRCLE -> sizePx * 0.22f
            IconShapeOption.SQUARE -> 0f
        }
        canvas.drawRoundRect(rect, radius, radius, maskPaint)

        val inset = sizePx * paddingRatio.coerceIn(0.05f, 0.35f)
        if (foregroundBitmap != null) {
            val dest = RectF(inset, inset, sizePx - inset, sizePx - inset)
            val src = Rect(0, 0, foregroundBitmap.width, foregroundBitmap.height)
            val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(foregroundBitmap, src, dest, fgPaint)
        } else {
            val label = monogramText.trim().take(3).ifEmpty { "NX" }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foregroundColorInt
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                textSize = if (label.length <= 2) sizePx * 0.42f else sizePx * 0.30f
            }
            val fm = textPaint.fontMetrics
            val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, sizePx / 2f, baseline, textPaint)
        }

        return out
    }

    /**
     * Exports standard Android mipmap density icons (`mdpi`..`xxxhdpi`) into [projectRoot]/app/src/main/res/.
     */
    fun exportAndroidLauncherIcons(
        projectRoot: File,
        backgroundColorInt: Int,
        foregroundColorInt: Int,
        shape: IconShapeOption,
        monogramText: String,
        foregroundBitmap: Bitmap?
    ): List<File> {
        val densities = listOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        )
        val resDir = File(projectRoot, "app/src/main/res")
        val written = mutableListOf<File>()

        densities.forEach { (folder, px) ->
            val dir = File(resDir, folder)
            dir.mkdirs()

            val squareOrSelected = generateLauncherIconBitmap(
                px, backgroundColorInt, foregroundColorInt, shape, monogramText, foregroundBitmap
            )
            val roundIcon = generateLauncherIconBitmap(
                px, backgroundColorInt, foregroundColorInt, IconShapeOption.CIRCLE, monogramText, foregroundBitmap
            )

            val normalFile = File(dir, "ic_launcher.png")
            FileOutputStream(normalFile).use { it.write(encodeBitmap(squareOrSelected, ImageExportFormat.PNG)) }
            written.add(normalFile)

            val roundFile = File(dir, "ic_launcher_round.png")
            FileOutputStream(roundFile).use { it.write(encodeBitmap(roundIcon, ImageExportFormat.PNG)) }
            written.add(roundFile)
        }
        return written
    }

    /**
     * Generates splash screen logo PNG + splash_colors.xml into Android project resources.
     */
    fun exportSplashAssets(
        projectRoot: File,
        bgColorHex: String,
        titleText: String,
        textColorHex: String,
        logoBitmap: Bitmap?
    ): List<File> {
        val resDir = File(projectRoot, "app/src/main/res")
        val drawableDir = File(resDir, "drawable").apply { mkdirs() }
        val valuesDir = File(resDir, "values").apply { mkdirs() }
        val written = mutableListOf<File>()

        // Generate splash logo bitmap (512x512)
        val splashLogo = logoBitmap?.let { resizeBitmap(it, 512, 512) }
            ?: generateLauncherIconBitmap(
                sizePx = 512,
                backgroundColorInt = parseSvgColor(bgColorHex),
                foregroundColorInt = parseSvgColor(textColorHex),
                shape = IconShapeOption.CIRCLE,
                monogramText = titleText.take(2).uppercase().ifBlank { "NX" }
            )

        val logoFile = File(drawableDir, "splash_logo.png")
        FileOutputStream(logoFile).use { it.write(encodeBitmap(splashLogo, ImageExportFormat.PNG)) }
        written.add(logoFile)

        val colorsXml = File(valuesDir, "splash_colors.xml")
        colorsXml.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="splash_background">$bgColorHex</color>
                <color name="splash_title_color">$textColorHex</color>
                <string name="splash_title">${titleText.replace("&", "&amp;").replace("<", "&lt;")}</string>
            </resources>
            """.trimIndent()
        )
        written.add(colorsXml)
        return written
    }

    fun listProjectVisualAssets(projectRoot: File): List<ProjectAssetEntry> {
        if (!projectRoot.exists()) return emptyList()
        val validExts = setOf("png", "jpg", "jpeg", "webp", "svg")
        return projectRoot.walkTopDown()
            .filter { !it.absolutePath.contains("/.gradle") && !it.absolutePath.contains("/build/") }
            .filter { it.isFile && it.extension.lowercase() in validExts }
            .map { f ->
                ProjectAssetEntry(
                    name = f.name,
                    relativePath = f.canonicalPath.removePrefix(projectRoot.canonicalPath).trimStart(File.separatorChar),
                    absolutePath = f.absolutePath,
                    sizeBytes = f.length(),
                    extension = f.extension.lowercase()
                )
            }
            .sortedBy { it.relativePath }
            .toList()
    }

    fun toHex(colorInt: Int): String {
        return String.format("#%06X", 0xFFFFFF and colorInt)
    }
}
