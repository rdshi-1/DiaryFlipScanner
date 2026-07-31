package com.example.diaryflip.export

import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Writes a simple image-only PDF without decoding the full photographs into memory.
 * Each source JPEG is embedded directly in the PDF using DCTDecode.
 */
object StreamingJpegPdfWriter {
    private const val PAGE_WIDTH = 595f
    private const val PAGE_HEIGHT = 842f
    private const val SIDE_MARGIN = 28f
    private const val IMAGE_BOTTOM = 28f
    private const val IMAGE_TOP = 800f

    fun write(pages: List<File>, target: OutputStream) {
        require(pages.isNotEmpty()) { "There are no pages to export" }

        val imageMetadata = pages.map { file ->
            require(file.exists() && file.length() > 0L) { "Missing page image: ${file.name}" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) {
                "Could not read page image: ${file.name}"
            }
            ImageMetadata(file, options.outWidth, options.outHeight)
        }

        val objectCount = 2 + imageMetadata.size * 3
        val offsets = LongArray(objectCount + 1)
        val output = CountingOutputStream(BufferedOutputStream(target, 64 * 1024))

        output.writeAscii("%PDF-1.4\n")
        output.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        writeObject(output, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>")

        val kids = imageMetadata.indices.joinToString(" ") { index ->
            "${pageObjectNumber(index)} 0 R"
        }
        writeObject(
            output,
            offsets,
            2,
            "<< /Type /Pages /Kids [$kids] /Count ${imageMetadata.size} >>"
        )

        imageMetadata.forEachIndexed { index, metadata ->
            val pageObject = pageObjectNumber(index)
            val contentObject = pageObject + 1
            val imageObject = pageObject + 2

            val pageDictionary = buildString {
                append("<< /Type /Page /Parent 2 0 R ")
                append("/MediaBox [0 0 ${PAGE_WIDTH.toInt()} ${PAGE_HEIGHT.toInt()}] ")
                append("/Resources << ")
                append("/XObject << /Im0 $imageObject 0 R >> ")
                append("/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> ")
                append(">> ")
                append("/Contents $contentObject 0 R >>")
            }
            writeObject(output, offsets, pageObject, pageDictionary)

            val content = pageContent(index + 1, metadata.width, metadata.height)
                .toByteArray(StandardCharsets.US_ASCII)
            offsets[contentObject] = output.count
            output.writeAscii("$contentObject 0 obj\n")
            output.writeAscii("<< /Length ${content.size} >>\nstream\n")
            output.write(content)
            output.writeAscii("\nendstream\nendobj\n")

            offsets[imageObject] = output.count
            output.writeAscii("$imageObject 0 obj\n")
            output.writeAscii(
                "<< /Type /XObject /Subtype /Image " +
                    "/Width ${metadata.width} /Height ${metadata.height} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                    "/Filter /DCTDecode /Length ${metadata.file.length()} >>\nstream\n"
            )
            BufferedInputStream(metadata.file.inputStream(), 64 * 1024).use { input ->
                input.copyTo(output, 64 * 1024)
            }
            output.writeAscii("\nendstream\nendobj\n")
        }

        val xrefOffset = output.count
        output.writeAscii("xref\n0 ${objectCount + 1}\n")
        output.writeAscii("0000000000 65535 f \n")
        for (objectNumber in 1..objectCount) {
            output.writeAscii(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[objectNumber]))
        }
        output.writeAscii(
            "trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\n" +
                "startxref\n$xrefOffset\n%%EOF\n"
        )
        output.flush()
    }

    private fun pageContent(pageNumber: Int, imageWidth: Int, imageHeight: Int): String {
        val availableWidth = PAGE_WIDTH - SIDE_MARGIN * 2f
        val availableHeight = IMAGE_TOP - IMAGE_BOTTOM
        val scale = min(availableWidth / imageWidth, availableHeight / imageHeight)
        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val x = (PAGE_WIDTH - drawnWidth) / 2f
        val y = IMAGE_BOTTOM + (availableHeight - drawnHeight) / 2f

        return buildString {
            append("q\n")
            append(number(drawnWidth)).append(" 0 0 ")
                .append(number(drawnHeight)).append(' ')
                .append(number(x)).append(' ')
                .append(number(y)).append(" cm\n")
            append("/Im0 Do\nQ\n")
            append("BT\n/F1 10 Tf\n28 819 Td\n(Page $pageNumber) Tj\nET\n")
        }
    }

    private fun pageObjectNumber(index: Int): Int = 3 + index * 3

    private fun number(value: Float): String =
        String.format(java.util.Locale.US, "%.2f", value)

    private fun writeObject(
        output: CountingOutputStream,
        offsets: LongArray,
        objectNumber: Int,
        dictionary: String
    ) {
        offsets[objectNumber] = output.count
        output.writeAscii("$objectNumber 0 obj\n$dictionary\nendobj\n")
    }

    private data class ImageMetadata(
        val file: File,
        val width: Int,
        val height: Int
    )

    private class CountingOutputStream(delegate: OutputStream) : OutputStream() {
        private val delegate = delegate
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count += 1L
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            count += length.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()

        fun writeAscii(value: String) {
            write(value.toByteArray(StandardCharsets.US_ASCII))
        }
    }
}
