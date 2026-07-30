package com.example.diaryflip.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object ImageSplitter {

    data class SplitResult(
        val fingerprint: DocumentFingerprint,
        val leftPage: File,
        val rightPage: File
    )

    data class ResplitResult(
        val leftPage: File,
        val rightPage: File
    )

    fun splitSpread(
        spreadFile: File,
        outputDirectory: File,
        leftPageNumber: Int,
        keepSpread: Boolean
    ): SplitResult {
        val bitmap = loadOrientedBitmap(spreadFile)
        val fingerprint = DocumentFingerprint.from(bitmap)
        val result = saveSplitPages(
            bitmap = bitmap,
            outputDirectory = outputDirectory,
            leftPageNumber = leftPageNumber,
            splitFraction = 0.5f,
            gutterFraction = 0.018f
        )
        bitmap.recycle()
        // Original spreads are retained so the centre split can be corrected later in Review.
        return SplitResult(fingerprint, result.leftPage, result.rightPage)
    }

    /**
     * Recreates the two page images from the retained original spread. The split and gutter are
     * expressed as fractions of the full spread width.
     */
    fun resplitSpread(
        spreadFile: File,
        outputDirectory: File,
        leftPageNumber: Int,
        splitFraction: Float,
        gutterFraction: Float
    ): ResplitResult {
        val bitmap = loadOrientedBitmap(spreadFile)
        val result = saveSplitPages(
            bitmap = bitmap,
            outputDirectory = outputDirectory,
            leftPageNumber = leftPageNumber,
            splitFraction = splitFraction.coerceIn(0.30f, 0.70f),
            gutterFraction = gutterFraction.coerceIn(0f, 0.08f)
        )
        bitmap.recycle()
        return result
    }

    fun loadOrientedBitmap(file: File): Bitmap {
        val raw = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("Could not decode captured image")
        val bitmap = rotateFromExif(raw, file)
        if (bitmap !== raw) raw.recycle()
        return bitmap
    }

    private fun saveSplitPages(
        bitmap: Bitmap,
        outputDirectory: File,
        leftPageNumber: Int,
        splitFraction: Float,
        gutterFraction: Float
    ): ResplitResult {
        val centre = (bitmap.width * splitFraction).toInt().coerceIn(1, bitmap.width - 1)
        val halfGutter = (bitmap.width * gutterFraction / 2f).toInt().coerceAtLeast(0)
        val leftWidth = (centre - halfGutter).coerceAtLeast(1)
        val rightStart = (centre + halfGutter).coerceAtMost(bitmap.width - 1)
        val rightWidth = (bitmap.width - rightStart).coerceAtLeast(1)

        val left = Bitmap.createBitmap(bitmap, 0, 0, leftWidth, bitmap.height)
        val right = Bitmap.createBitmap(bitmap, rightStart, 0, rightWidth, bitmap.height)

        val leftFile = File(outputDirectory, "page_${leftPageNumber.toString().padStart(4, '0')}.jpg")
        val rightFile = File(outputDirectory, "page_${(leftPageNumber + 1).toString().padStart(4, '0')}.jpg")
        saveJpeg(left, leftFile)
        saveJpeg(right, rightFile)

        left.recycle()
        right.recycle()
        return ResplitResult(leftFile, rightFile)
    }

    private fun rotateFromExif(bitmap: Bitmap, file: File): Bitmap {
        val exif = ExifInterface(file)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
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

    private fun saveJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)) {
                error("Failed to save ${file.name}")
            }
        }
    }
}
