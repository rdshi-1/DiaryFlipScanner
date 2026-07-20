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

    fun splitSpread(
        spreadFile: File,
        outputDirectory: File,
        leftPageNumber: Int,
        keepSpread: Boolean
    ): SplitResult {
        val raw = BitmapFactory.decodeFile(spreadFile.absolutePath)
            ?: error("Could not decode captured image")
        val bitmap = rotateFromExif(raw, spreadFile)
        if (bitmap !== raw) raw.recycle()

        val fingerprint = DocumentFingerprint.from(bitmap)
        val gutter = (bitmap.width * 0.018f).toInt().coerceAtLeast(4)
        val centre = bitmap.width / 2
        val leftWidth = (centre - gutter).coerceAtLeast(1)
        val rightStart = (centre + gutter).coerceAtMost(bitmap.width - 1)
        val rightWidth = (bitmap.width - rightStart).coerceAtLeast(1)

        val left = Bitmap.createBitmap(bitmap, 0, 0, leftWidth, bitmap.height)
        val right = Bitmap.createBitmap(bitmap, rightStart, 0, rightWidth, bitmap.height)

        val leftFile = File(outputDirectory, "page_${leftPageNumber.toString().padStart(4, '0')}.jpg")
        val rightFile = File(outputDirectory, "page_${(leftPageNumber + 1).toString().padStart(4, '0')}.jpg")
        saveJpeg(left, leftFile)
        saveJpeg(right, rightFile)

        left.recycle()
        right.recycle()
        bitmap.recycle()
        if (!keepSpread) spreadFile.delete()

        return SplitResult(fingerprint, leftFile, rightFile)
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
