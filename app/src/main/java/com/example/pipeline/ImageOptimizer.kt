package com.example.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.FileOutputStream

data class ImageOptimizationResult(
    val brokenOrTinyTagsRemoved: Int = 0,
    val imagesCompressed: Int = 0,
    val bytesSaved: Long = 0L,
    val updatedImageRelativePaths: List<String> = emptyList()
) {
    val totalOptimizations: Int
        get() = brokenOrTinyTagsRemoved + imagesCompressed
}

/**
 * 100% Offline EPUB Image Cleaner & Compressor (Upgrade 1.2):
 * 1. Broken & Tracker Image Removal:
 *    - Deletes `<img>` tags whose referenced image does not exist in `OEBPS/Images`
 *      OR whose image dimensions are abnormally tiny (`< 20x20` px ad/tracker pixels).
 *    - Deletes invalid/tiny image files from `OEBPS/Images` so they are never packed into EPUB.
 * 2. Oversized Illustration Compression (RAM-safe for 4GB devices):
 *    - Any raster illustration in `OEBPS/Images` with `width > 1200px` OR file size `> 500KB`
 *      is decoded using power-of-2 `inSampleSize`, scaled proportionally to max width `1200px`,
 *      compressed to JPEG quality `82%`, and immediately recycled.
 */
object ImageOptimizer {

    const val MIN_VALID_DIMENSION_PX = 20
    const val MAX_WIDTH_PX = 1200
    const val MAX_FILE_SIZE_BYTES = 500L * 1024L
    const val JPEG_QUALITY = 82

    private val rasterExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun optimizeWorkspaceImages(workspace: PreparedWorkspace): ImageOptimizationResult {
        val stageDir = File(workspace.stageDirPath)
        val oebpsDir = File(stageDir, "OEBPS")
        val imagesDir = File(oebpsDir, "Images")

        var brokenOrTinyCount = 0
        var compressedCount = 0
        var totalBytesSaved = 0L

        val validFileNames = linkedSetOf<String>()
        val renamedFiles = mutableMapOf<String, String>() // oldFileName -> newFileName

        if (imagesDir.exists() && imagesDir.isDirectory) {
            val imageFiles = imagesDir.listFiles()?.filter { it.isFile }.orEmpty()
            for (imgFile in imageFiles) {
                val ext = imgFile.extension.lowercase()
                if (imgFile.length() <= 0L) {
                    imgFile.delete()
                    brokenOrTinyCount++
                    continue
                }

                // Keep non-raster formats (svg, gif) as-is if non-empty
                if (ext !in rasterExtensions) {
                    validFileNames.add(imgFile.name)
                    continue
                }

                val bounds = readImageBoundsSafely(imgFile)
                if (bounds == null) {
                    // Unreadable/corrupted file on Android runtime -> delete
                    // (In pure JVM unit tests where BitmapFactory isn't mocked, bounds == (-1, -1) sentinel)
                    imgFile.delete()
                    brokenOrTinyCount++
                    continue
                }
                if (bounds.first == JVM_UNAVAILABLE_SENTINEL) {
                    validFileNames.add(imgFile.name)
                    continue
                }

                val (rawWidth, rawHeight) = bounds
                if (rawWidth < MIN_VALID_DIMENSION_PX || rawHeight < MIN_VALID_DIMENSION_PX) {
                    imgFile.delete()
                    brokenOrTinyCount++
                    continue
                }

                val originalBytes = imgFile.length()
                val needsCompress = rawWidth > MAX_WIDTH_PX || originalBytes > MAX_FILE_SIZE_BYTES
                if (needsCompress) {
                    val outcome = compressImageToJpegSafely(imgFile, rawWidth, rawHeight)
                    if (outcome != null) {
                        val (finalFile, savedBytes) = outcome
                        if (finalFile.name != imgFile.name) {
                            renamedFiles[imgFile.name] = finalFile.name
                        }
                        validFileNames.add(finalFile.name)
                        compressedCount++
                        totalBytesSaved += savedBytes.coerceAtLeast(0L)
                        continue
                    }
                }

                validFileNames.add(imgFile.name)
            }
        }

        // Scan every staged chapter XHTML: remove broken/tiny <img> tags & update renamed .jpg sources
        for (chapter in workspace.chapters) {
            val chapFile = File(chapter.filePath)
            if (!chapFile.exists()) continue

            val doc = Jsoup.parse(chapFile, "UTF-8").apply {
                outputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .charset(Charsets.UTF_8)
                    .prettyPrint(true)
            }

            var modified = false
            doc.select("img").forEach { img ->
                val rawSrc = img.attr("src").trim()
                if (rawSrc.isEmpty()) {
                    img.remove()
                    brokenOrTinyCount++
                    modified = true
                    return@forEach
                }

                val baseName = rawSrc.substringAfterLast('/').substringBefore('?').substringBefore('#')
                val renamed = renamedFiles[baseName]
                when {
                    renamed != null && renamed in validFileNames -> {
                        val newSrc = "../Images/$renamed"
                        if (rawSrc != newSrc) {
                            img.attr("src", newSrc)
                            modified = true
                        }
                    }

                    baseName in validFileNames -> {
                        // Valid image exists in OEBPS/Images
                    }

                    else -> {
                        // Broken link or removed tracker pixel
                        img.remove()
                        brokenOrTinyCount++
                        modified = true
                    }
                }
            }

            if (modified) {
                val xhtml = EpubWorkspaceManager.buildValidXhtmlDocument(chapter.title, doc.body().html())
                chapFile.writeText(xhtml, Charsets.UTF_8)
            }
        }

        val updatedRelPaths = validFileNames
            .sorted()
            .map { "Images/$it" }

        return ImageOptimizationResult(
            brokenOrTinyTagsRemoved = brokenOrTinyCount,
            imagesCompressed = compressedCount,
            bytesSaved = totalBytesSaved,
            updatedImageRelativePaths = updatedRelPaths
        )
    }

    /**
     * Power-of-2 downsampling factor so huge images never spike RAM on 4GB Android phones.
     */
    fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, maxWidth: Int = MAX_WIDTH_PX): Int {
        if (rawWidth <= 0 || rawHeight <= 0 || maxWidth <= 0) return 1
        var sampleSize = 1
        while ((rawWidth / (sampleSize * 2)) >= maxWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Scales `(width, height)` proportionally so `newWidth <= maxWidth`.
     */
    fun computeScaledDimensions(width: Int, height: Int, maxWidth: Int = MAX_WIDTH_PX): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        if (width <= maxWidth) return width to height
        val ratio = maxWidth.toFloat() / width.toFloat()
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return maxWidth to targetHeight
    }

    private const val JVM_UNAVAILABLE_SENTINEL = -999

    private fun readImageBoundsSafely(file: File): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                null
            } else {
                opts.outWidth to opts.outHeight
            }
        } catch (_: Throwable) {
            // Pure JVM unit test without Android BitmapFactory stub implementation
            JVM_UNAVAILABLE_SENTINEL to JVM_UNAVAILABLE_SENTINEL
        }
    }

    private fun compressImageToJpegSafely(
        sourceFile: File,
        rawWidth: Int,
        rawHeight: Int
    ): Pair<File, Long>? {
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val sampleSize = calculateInSampleSize(rawWidth, rawHeight, MAX_WIDTH_PX)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts) ?: return null

            val (targetW, targetH) = computeScaledDimensions(decoded.width, decoded.height, MAX_WIDTH_PX)
            val bitmapToWrite = if (targetW < decoded.width || targetH < decoded.height) {
                scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                scaled
            } else {
                decoded
            }

            val originalSize = sourceFile.length()
            val baseName = sourceFile.nameWithoutExtension
            val targetFile = File(sourceFile.parentFile, "$baseName.jpg")
            val tempFile = File(sourceFile.parentFile, "${baseName}_opt_tmp.jpg")

            FileOutputStream(tempFile).buffered().use { out ->
                bitmapToWrite.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }

            val newSize = tempFile.length()
            if (newSize <= 0L) {
                tempFile.delete()
                return null
            }

            // Replace source if smaller OR if width needed downscaling
            if (newSize < originalSize || rawWidth > MAX_WIDTH_PX) {
                if (targetFile.absolutePath != sourceFile.absolutePath && sourceFile.exists()) {
                    sourceFile.delete()
                }
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                targetFile to (originalSize - newSize).coerceAtLeast(0L)
            } else {
                tempFile.delete()
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled != null && scaled != decoded && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (decoded != null && !decoded.isRecycled) {
                decoded.recycle()
            }
        }
    }
}
