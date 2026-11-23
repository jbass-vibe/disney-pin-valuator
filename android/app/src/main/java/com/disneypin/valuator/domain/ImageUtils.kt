package com.disneypin.valuator.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.disneypin.valuator.data.model.BoundingBoxNorm
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageUtils {

    fun descaleBoundingBox(
        box: BoundingBoxNorm,
        imageWidth: Int,
        imageHeight: Int,
        paddingFraction: Double = 0.06
    ): Rect {
        val w = imageWidth.coerceAtLeast(1)
        val h = imageHeight.coerceAtLeast(1)
        val rawLeft = (box.xmin.coerceIn(0, 1000) / 1000.0) * w
        val rawTop = (box.ymin.coerceIn(0, 1000) / 1000.0) * h
        val rawRight = (box.xmax.coerceIn(0, 1000) / 1000.0) * w
        val rawBottom = (box.ymax.coerceIn(0, 1000) / 1000.0) * h

        val boxW = (rawRight - rawLeft).coerceAtLeast(1.0)
        val boxH = (rawBottom - rawTop).coerceAtLeast(1.0)
        val padX = boxW * paddingFraction
        val padY = boxH * paddingFraction

        val left = (rawLeft - padX).roundToInt().coerceIn(0, w - 1)
        val top = (rawTop - padY).roundToInt().coerceIn(0, h - 1)
        val right = (rawRight + padX).roundToInt().coerceIn(left + 1, w)
        val bottom = (rawBottom + padY).roundToInt().coerceIn(top + 1, h)
        return Rect(left, top, right, bottom)
    }

    fun decodeNormalizedBitmap(
        context: Context,
        uri: Uri,
        maxEdgePx: Int = 1600
    ): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxEdgePx)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rotated = applyExifRotation(decoded, orientation)
        return downscaleBitmap(rotated, maxEdgePx)
    }

    fun decodeNormalizedFile(filePath: String, maxEdgePx: Int = 1600): Bitmap? {
        val file = File(filePath)
        if (!file.exists()) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxEdgePx)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(filePath, decodeOpts) ?: return null

        val orientation = runCatching {
            ExifInterface(filePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotated = applyExifRotation(decoded, orientation)
        return downscaleBitmap(rotated, maxEdgePx)
    }

    fun saveNormalizedScanImage(context: Context, uri: Uri, filename: String): Pair<File, Bitmap>? {
        val bitmap = decodeNormalizedBitmap(context, uri, maxEdgePx = 1600) ?: return null
        val dir = File(context.filesDir, "scan_photos").apply { mkdirs() }
        val outFile = File(dir, filename)
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }
        return outFile to bitmap
    }

    fun bitmapToJpegBase64(bitmap: Bitmap, quality: Int = 85): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun cropBitmap(source: Bitmap, box: BoundingBoxNorm): Bitmap {
        val rect = descaleBoundingBox(box, source.width, source.height)
        return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
    }

    fun cropAndSavePinThumbnail(
        source: Bitmap,
        box: BoundingBoxNorm,
        outFile: File,
        maxDim: Int = 320
    ): Bitmap {
        val cropped = cropBitmap(source, box)
        val scaled = downscaleBitmap(cropped, maxDim)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos)
        }
        return scaled
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var inSampleSize = 1
        val maxSide = max(width, height)
        while (maxSide / (inSampleSize * 2) >= maxEdge) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / maxSide.toFloat()
        val targetW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
