package com.example.diaryflip

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.diaryflip.data.SessionRepository
import com.example.diaryflip.databinding.ActivityReviewBinding
import com.example.diaryflip.databinding.DialogAdjustCropBinding
import com.example.diaryflip.databinding.DialogAdjustSplitBinding
import com.example.diaryflip.databinding.ItemReviewPageBinding
import com.example.diaryflip.export.StreamingJpegPdfWriter
import com.example.diaryflip.network.TranscriptionWorker
import com.example.diaryflip.scanner.ImageSplitter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewBinding
    private lateinit var pagesAdapter: PagesAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var session: File? = null

    private val createPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) writePdfToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pagesAdapter = PagesAdapter(
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) },
            onDeleteRequested = { position -> confirmDelete(position) },
            onAdjustCropRequested = { position -> openCropDialog(position) },
            onResplitRequested = { position -> openResplitDialog(position) }
        )
        binding.pagesList.layoutManager = LinearLayoutManager(this)
        binding.pagesList.adapter = pagesAdapter

        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                pagesAdapter.movePage(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                saveCurrentOrder()
                pagesAdapter.refreshPageNumbers()
            }
        }
        itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(binding.pagesList)

        binding.refreshButton.setOnClickListener {
            pagesAdapter.saveAllDrafts()
            loadPages()
        }
        binding.shareButton.setOnClickListener { shareDiaryText() }
        binding.exportPdfButton.setOnClickListener { exportDiaryPdf() }
        loadPages()
    }

    override fun onPause() {
        pagesAdapter.saveAllDrafts()
        saveCurrentOrder()
        super.onPause()
    }

    private fun loadPages() {
        session = SessionRepository.currentSession(this)
        val activeSession = session
        if (activeSession == null) {
            pagesAdapter.submitPages(emptyList())
            showEmptyMessage("No diary session has been created yet.")
            return
        }

        val pages = SessionRepository.pageFiles(activeSession)
        pagesAdapter.submitPages(pages)
        if (pages.isEmpty()) {
            showEmptyMessage("No pages have been captured in this session.")
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.pagesList.visibility = View.VISIBLE
        }
    }

    private fun confirmDelete(position: Int) {
        val page = pagesAdapter.pageAt(position) ?: return
        val displayNumber = position + 1
        AlertDialog.Builder(this)
            .setTitle("Delete page $displayNumber?")
            .setMessage("The page image and its transcription will be permanently removed from this diary.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val activeSession = session ?: return@setPositiveButton
                pagesAdapter.saveDraft(page)
                if (SessionRepository.deletePage(activeSession, page)) {
                    pagesAdapter.removePage(position)
                    saveCurrentOrder()
                    Toast.makeText(this, "Page $displayNumber deleted", Toast.LENGTH_SHORT).show()
                    if (pagesAdapter.itemCount == 0) {
                        showEmptyMessage("No pages remain in this session.")
                    }
                } else {
                    Toast.makeText(this, "Could not delete this page", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun openCropDialog(position: Int) {
        val page = pagesAdapter.pageAt(position) ?: return
        val originalBitmap = BitmapFactory.decodeFile(page.absolutePath)
        if (originalBitmap == null) {
            Toast.makeText(this, "Could not open this page image", Toast.LENGTH_LONG).show()
            return
        }

        val dialogBinding = DialogAdjustCropBinding.inflate(layoutInflater)
        var previewBitmap: Bitmap? = null
        var leftTrim = 0
        var rightTrim = 0
        var topTrim = 0
        var bottomTrim = 0

        fun cropRect(): Rect {
            val leftPx = (originalBitmap.width * (leftTrim.coerceIn(0, 40) / 100f)).toInt()
            val rightPx = (originalBitmap.width * (rightTrim.coerceIn(0, 40) / 100f)).toInt()
            val topPx = (originalBitmap.height * (topTrim.coerceIn(0, 25) / 100f)).toInt()
            val bottomPx = (originalBitmap.height * (bottomTrim.coerceIn(0, 25) / 100f)).toInt()

            val minWidth = (originalBitmap.width * 0.20f).toInt().coerceAtLeast(120)
            val minHeight = (originalBitmap.height * 0.20f).toInt().coerceAtLeast(120)

            var left = leftPx
            var top = topPx
            var right = (originalBitmap.width - rightPx).coerceAtLeast(left + minWidth)
            var bottom = (originalBitmap.height - bottomPx).coerceAtLeast(top + minHeight)

            if (right > originalBitmap.width) right = originalBitmap.width
            if (bottom > originalBitmap.height) bottom = originalBitmap.height
            if (right - left < minWidth) left = (right - minWidth).coerceAtLeast(0)
            if (bottom - top < minHeight) top = (bottom - minHeight).coerceAtLeast(0)
            return Rect(left, top, right, bottom)
        }

        fun renderPreview() {
            dialogBinding.leftTrimLabel.text = "Trim left edge: $leftTrim%"
            dialogBinding.rightTrimLabel.text = "Trim right edge: $rightTrim%"
            dialogBinding.topTrimLabel.text = "Trim top edge: $topTrim%"
            dialogBinding.bottomTrimLabel.text = "Trim bottom edge: $bottomTrim%"

            previewBitmap?.recycle()
            val rect = cropRect()
            previewBitmap = Bitmap.createBitmap(
                originalBitmap,
                rect.left,
                rect.top,
                rect.width(),
                rect.height()
            )
            dialogBinding.cropPreviewImage.setImageBitmap(previewBitmap)
        }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                when (seekBar?.id) {
                    R.id.leftTrimSeek -> leftTrim = progress
                    R.id.rightTrimSeek -> rightTrim = progress
                    R.id.topTrimSeek -> topTrim = progress
                    R.id.bottomTrimSeek -> bottomTrim = progress
                }
                renderPreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

        dialogBinding.leftTrimSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.rightTrimSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.topTrimSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.bottomTrimSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.resetCropButton.setOnClickListener {
            leftTrim = 0
            rightTrim = 0
            topTrim = 0
            bottomTrim = 0
            dialogBinding.leftTrimSeek.progress = 0
            dialogBinding.rightTrimSeek.progress = 0
            dialogBinding.topTrimSeek.progress = 0
            dialogBinding.bottomTrimSeek.progress = 0
            renderPreview()
        }
        renderPreview()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Adjust crop for page ${position + 1}")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val rect = cropRect()
                    val cropped = Bitmap.createBitmap(
                        originalBitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                    saveJpeg(cropped, page)
                    cropped.recycle()
                    pagesAdapter.notifyItemChanged(position)
                    Toast.makeText(this, "Crop updated for page ${position + 1}", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } catch (error: Exception) {
                    Toast.makeText(
                        this,
                        error.message ?: "Could not save the new crop",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        dialog.setOnDismissListener {
            previewBitmap?.recycle()
            originalBitmap.recycle()
        }
        dialog.show()
    }

    private fun openResplitDialog(position: Int) {
        val selectedPage = pagesAdapter.pageAt(position) ?: return
        val activeSession = session ?: return
        val originalPageNumber = SessionRepository.pageNumber(selectedPage)
        if (originalPageNumber <= 0) {
            Toast.makeText(this, "Could not identify the original spread", Toast.LENGTH_LONG).show()
            return
        }

        val spreadNumber = ((originalPageNumber - 1) / 2) + 1
        val leftPageNumber = ((originalPageNumber - 1) / 2) * 2 + 1
        val spreadFile = File(activeSession, "spread_${spreadNumber.toString().padStart(4, '0')}.jpg")
        if (!spreadFile.exists()) {
            Toast.makeText(
                this,
                "The original two-page photograph was not retained for this spread.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val spreadBitmap = try {
            ImageSplitter.loadOrientedBitmap(spreadFile)
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Could not open the original spread", Toast.LENGTH_LONG).show()
            return
        }

        val dialogBinding = DialogAdjustSplitBinding.inflate(layoutInflater)
        var splitPercent = 50
        var gutterTenths = 18

        fun renderSplit() {
            val splitFraction = splitPercent / 100f
            val gutterFraction = gutterTenths / 1000f
            dialogBinding.splitPositionLabel.text = "Centre position: $splitPercent%"
            dialogBinding.gutterWidthLabel.text =
                "Omit centre gutter: ${String.format(Locale.UK, "%.1f", gutterTenths / 10f)}%"
            dialogBinding.splitPreview.splitFraction = splitFraction
            dialogBinding.splitPreview.gutterFraction = gutterFraction
        }

        dialogBinding.splitPreview.bitmap = spreadBitmap
        dialogBinding.splitPositionSeek.progress = 20
        dialogBinding.gutterWidthSeek.progress = gutterTenths

        dialogBinding.splitPositionSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    splitPercent = 30 + progress
                    renderSplit()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        dialogBinding.gutterWidthSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gutterTenths = progress
                    renderSplit()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        dialogBinding.resetSplitButton.setOnClickListener {
            splitPercent = 50
            gutterTenths = 18
            dialogBinding.splitPositionSeek.progress = 20
            dialogBinding.gutterWidthSeek.progress = 18
            renderSplit()
        }
        renderSplit()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Re-split original spread")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save both pages", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val leftPath = File(activeSession, "page_${leftPageNumber.toString().padStart(4, '0')}.jpg")
                    val rightPath = File(activeSession, "page_${(leftPageNumber + 1).toString().padStart(4, '0')}.jpg")
                    val leftPreviouslyExisted = leftPath.exists()
                    val rightPreviouslyExisted = rightPath.exists()

                    val result = ImageSplitter.resplitSpread(
                        spreadFile = spreadFile,
                        outputDirectory = activeSession,
                        leftPageNumber = leftPageNumber,
                        splitFraction = splitPercent / 100f,
                        gutterFraction = gutterTenths / 1000f
                    )

                    // Do not bring back a page the user deliberately deleted.
                    if (!leftPreviouslyExisted) result.leftPage.delete()
                    if (!rightPreviouslyExisted) result.rightPage.delete()

                    if (leftPreviouslyExisted) {
                        pagesAdapter.refreshImage(result.leftPage)
                        queueRetranscription(result.leftPage, leftPageNumber)
                    }
                    if (rightPreviouslyExisted) {
                        pagesAdapter.refreshImage(result.rightPage)
                        queueRetranscription(result.rightPage, leftPageNumber + 1)
                    }
                    Toast.makeText(this, "Both page crops updated", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } catch (error: Exception) {
                    Toast.makeText(
                        this,
                        error.message ?: "Could not re-split this spread",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.setOnDismissListener { spreadBitmap.recycle() }
        dialog.show()
    }

    private fun queueRetranscription(page: File, originalPageNumber: Int) {
        val endpoint = SessionRepository.endpoint(this)
        if (endpoint.isBlank()) return
        TranscriptionWorker.enqueue(
            this,
            page,
            originalPageNumber,
            endpoint,
            SessionRepository.token(this)
        )
    }

    private fun exportDiaryPdf() {
        val activeSession = session ?: return
        pagesAdapter.saveAllDrafts()
        saveCurrentOrder()

        val pages = SessionRepository.pageFiles(activeSession)
        if (pages.isEmpty()) {
            Toast.makeText(this, "There are no pages to export", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        createPdfLauncher.launch("DiaryFlip_$timestamp.pdf")
    }

    private fun writePdfToUri(uri: Uri) {
        val activeSession = SessionRepository.currentSession(this)
        val pages = activeSession?.let(SessionRepository::pageFiles).orEmpty()
        if (pages.isEmpty()) {
            Toast.makeText(this, "There are no pages to export", Toast.LENGTH_SHORT).show()
            return
        }

        binding.exportPdfButton.isEnabled = false
        binding.exportPdfButton.text = "Creating PDF…"

        Thread {
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    StreamingJpegPdfWriter.write(pages, output)
                } ?: error("Android could not open the selected PDF file")

                runOnUiThread {
                    restorePdfButton()
                    Toast.makeText(
                        this,
                        "PDF saved successfully",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: OutOfMemoryError) {
                deleteFailedPdf(uri)
                runOnUiThread {
                    restorePdfButton()
                    Toast.makeText(
                        this,
                        "The phone ran out of memory while exporting. No PDF was saved.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Exception) {
                deleteFailedPdf(uri)
                runOnUiThread {
                    restorePdfButton()
                    Toast.makeText(
                        this,
                        error.message ?: "Could not create the PDF",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun deleteFailedPdf(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
            // Some document providers do not allow deletion; leaving an empty file is safer than crashing.
        }
    }

    private fun restorePdfButton() {
        if (!isFinishing && !isDestroyed) {
            binding.exportPdfButton.isEnabled = true
            binding.exportPdfButton.text = "Export PDF"
        }
    }

    private fun saveCurrentOrder() {
        val activeSession = session ?: return
        SessionRepository.savePageOrder(activeSession, pagesAdapter.currentPages())
    }

    private fun shareDiaryText() {
        val activeSession = session ?: return
        pagesAdapter.saveAllDrafts()
        saveCurrentOrder()

        val pages = SessionRepository.pageFiles(activeSession)
        if (pages.isEmpty()) {
            Toast.makeText(this, "There are no pages to share", Toast.LENGTH_SHORT).show()
            return
        }

        val combined = buildString {
            pages.forEachIndexed { index, page ->
                val transcript = SessionRepository.transcriptFile(page)
                append("PAGE ${index + 1}\n")
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

    private fun showEmptyMessage(message: String) {
        binding.emptyMessage.text = message
        binding.emptyMessage.visibility = View.VISIBLE
        binding.pagesList.visibility = View.GONE
    }

    private fun saveJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)) {
                error("Failed to save ${file.name}")
            }
        }
    }

    private inner class PagesAdapter(
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
        private val onDeleteRequested: (Int) -> Unit,
        private val onAdjustCropRequested: (Int) -> Unit,
        private val onResplitRequested: (Int) -> Unit
    ) : RecyclerView.Adapter<PagesAdapter.PageViewHolder>() {

        private val pages = mutableListOf<File>()
        private val drafts = mutableMapOf<String, String>()

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = pages[position].absolutePath.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val itemBinding = ItemReviewPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PageViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(pages[position], position)
        }

        override fun getItemCount(): Int = pages.size

        fun submitPages(newPages: List<File>) {
            pages.clear()
            pages.addAll(newPages)
            drafts.keys.retainAll(newPages.map { it.absolutePath }.toSet())
            notifyDataSetChanged()
        }

        fun currentPages(): List<File> = pages.toList()
        fun pageAt(position: Int): File? = pages.getOrNull(position)

        fun refreshImage(page: File) {
            val position = pages.indexOfFirst { it.absolutePath == page.absolutePath }
            if (position >= 0) notifyItemChanged(position)
        }

        fun movePage(from: Int, to: Int) {
            if (from !in pages.indices || to !in pages.indices || from == to) return
            val moving = pages.removeAt(from)
            pages.add(to, moving)
            notifyItemMoved(from, to)
        }

        fun removePage(position: Int) {
            if (position !in pages.indices) return
            val removed = pages.removeAt(position)
            drafts.remove(removed.absolutePath)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, pages.size - position)
        }

        fun refreshPageNumbers() {
            notifyItemRangeChanged(0, pages.size, PAGE_NUMBER_PAYLOAD)
        }

        fun saveDraft(page: File) {
            val text = drafts[page.absolutePath] ?: return
            SessionRepository.transcriptFile(page).writeText(text)
        }

        fun saveAllDrafts() {
            pages.forEach(::saveDraft)
        }

        inner class PageViewHolder(
            private val itemBinding: ItemReviewPageBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {
            private var suppressTextChanges = false
            private var textWatcher: android.text.TextWatcher? = null

            fun bind(page: File, position: Int) {
                itemBinding.pageTitle.text = "Page ${position + 1}"
                itemBinding.pageImage.contentDescription = "Photograph of page ${position + 1}"

                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                itemBinding.pageImage.setImageBitmap(
                    BitmapFactory.decodeFile(page.absolutePath, options)
                )

                val transcriptFile = SessionRepository.transcriptFile(page)
                val initialText = drafts[page.absolutePath]
                    ?: if (transcriptFile.exists()) transcriptFile.readText() else "Transcription pending…"

                textWatcher?.let(itemBinding.transcriptEditor::removeTextChangedListener)
                suppressTextChanges = true
                itemBinding.transcriptEditor.setText(initialText)
                suppressTextChanges = false

                textWatcher = itemBinding.transcriptEditor.doAfterTextChanged { editable ->
                    if (!suppressTextChanges) {
                        drafts[page.absolutePath] = editable?.toString().orEmpty()
                    }
                }

                itemBinding.saveButton.setOnClickListener {
                    val text = itemBinding.transcriptEditor.text?.toString().orEmpty()
                    drafts[page.absolutePath] = text
                    transcriptFile.writeText(text)
                    Toast.makeText(
                        this@ReviewActivity,
                        "Page ${bindingAdapterPosition + 1} saved",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                itemBinding.adjustCropButton.setOnClickListener {
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        onAdjustCropRequested(currentPosition)
                    }
                }

                itemBinding.resplitButton.setOnClickListener {
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        onResplitRequested(currentPosition)
                    }
                }

                itemBinding.deleteButton.setOnClickListener {
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        onDeleteRequested(currentPosition)
                    }
                }

                itemBinding.moveButton.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag(this)
                    }
                    false
                }
            }
        }

        override fun onBindViewHolder(
            holder: PageViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.contains(PAGE_NUMBER_PAYLOAD)) {
                holder.itemView.findViewById<android.widget.TextView>(R.id.pageTitle).text =
                    "Page ${position + 1}"
                holder.itemView.findViewById<android.widget.ImageView>(R.id.pageImage).contentDescription =
                    "Photograph of page ${position + 1}"
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }
    }

    private companion object {
        const val PAGE_NUMBER_PAYLOAD = "page_number"
    }
}
