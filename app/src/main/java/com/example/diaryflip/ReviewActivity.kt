package com.example.diaryflip

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.diaryflip.data.SessionRepository
import com.example.diaryflip.databinding.ActivityReviewBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class ReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.refreshButton.setOnClickListener { loadPages() }
        binding.shareButton.setOnClickListener { shareDiaryText() }
        loadPages()
    }

    private fun loadPages() {
        binding.pagesContainer.removeAllViews()
        val session = SessionRepository.currentSession(this)
        if (session == null) {
            addEmptyMessage("No diary session has been created yet.")
            return
        }

        val pages = SessionRepository.pageFiles(session)
        if (pages.isEmpty()) {
            addEmptyMessage("No pages have been captured in this session.")
            return
        }

        pages.forEach { pageFile ->
            val pageNumber = SessionRepository.pageNumber(pageFile)
            val transcriptFile = SessionRepository.transcriptFile(pageFile)

            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(2).toFloat()
                setContentPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            content.addView(TextView(this).apply {
                text = "Page $pageNumber"
                textSize = 21f
                setTextColor(getColor(R.color.diary_ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(280)
                ).apply { topMargin = dp(10) }
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                setImageBitmap(BitmapFactory.decodeFile(pageFile.absolutePath, options))
                contentDescription = "Photograph of page $pageNumber"
            }
            content.addView(image)

            val editor = EditText(this).apply {
                minLines = 6
                textSize = 17f
                setTextColor(getColor(R.color.diary_ink))
                setBackgroundColor(0x00FFFFFF)
                setPadding(0, dp(12), 0, dp(8))
                setText(
                    if (transcriptFile.exists()) transcriptFile.readText()
                    else "Transcription pending…"
                )
            }
            content.addView(editor)

            content.addView(MaterialButton(this).apply {
                text = "Save page text"
                setOnClickListener {
                    val text = editor.text?.toString().orEmpty()
                    transcriptFile.writeText(text)
                    Toast.makeText(this@ReviewActivity, "Page $pageNumber saved", Toast.LENGTH_SHORT).show()
                }
            })

            card.addView(content)
            binding.pagesContainer.addView(card)
        }
    }

    private fun shareDiaryText() {
        val session = SessionRepository.currentSession(this) ?: return
        val pages = SessionRepository.pageFiles(session)
        val combined = buildString {
            pages.forEach { page ->
                val number = SessionRepository.pageNumber(page)
                val transcript = SessionRepository.transcriptFile(page)
                append("PAGE $number\n")
                append(if (transcript.exists()) transcript.readText().trim() else "[Transcription pending]")
                append("\n\n")
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DiaryFlip transcription")
            putExtra(Intent.EXTRA_TEXT, combined)
        }
        startActivity(Intent.createChooser(intent, "Share diary transcription"))
    }

    private fun addEmptyMessage(message: String) {
        binding.pagesContainer.addView(TextView(this).apply {
            text = message
            textSize = 18f
            setTextColor(getColor(R.color.diary_ink))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
