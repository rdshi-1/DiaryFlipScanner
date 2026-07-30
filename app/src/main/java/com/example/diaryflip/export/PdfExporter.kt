package com.example.diaryflip.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.diaryflip.data.SessionRepository
import java.io.File
import java.io.FileOutputStream

object PdfExporter {

    fun exportSessionPdf(context: Context, session: File, pages: List<File>): File {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 32
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            isFakeBoldText = true
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
        }

        pages.forEachIndexed { index, pageFile ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val pdfPage = pdf.startPage(pageInfo)
            val canvas = pdfPage.canvas

            var y = margin + 10
            canvas.drawText("Page ${index + 1}", margin.toFloat(), y.toFloat(), titlePaint)
            y += 18

            val bitmap = BitmapFactory.decodeFile(pageFile.absolutePath)
            if (bitmap != null) {
                val maxImageWidth = pageWidth - (margin * 2)
                val maxImageHeight = 340
                val scale = minOf(
                    maxImageWidth.toFloat() / bitmap.width,
                    maxImageHeight.toFloat() / bitmap.height
                )
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val imageRect = RectF(
                    margin.toFloat(),
                    y.toFloat() + 10f,
                    margin + drawWidth,
                    y + 10f + drawHeight
                )
                canvas.drawBitmap(bitmap, null, imageRect, null)
                y = imageRect.bottom.toInt() + 22
                bitmap.recycle()
            }

            val transcript = SessionRepository.transcriptFile(pageFile)
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.ifBlank { "[No transcription yet]" }
                ?: "[Transcription pending]"

            val availableWidth = pageWidth - (margin * 2)
            val layout = StaticLayout.Builder
                .obtain(transcript, 0, transcript.length, bodyPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(margin.toFloat(), y.toFloat())
            layout.draw(canvas)
            canvas.restore()

            pdf.finishPage(pdfPage)
        }

        val output = File(session, "DiaryFlip_export.pdf")
        FileOutputStream(output).use { pdf.writeTo(it) }
        pdf.close()
        return output
    }
}
