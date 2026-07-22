package com.example.diaryflip

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.diaryflip.data.SessionRepository
import com.example.diaryflip.databinding.ActivityReviewBinding
import com.example.diaryflip.databinding.ItemReviewPageBinding
import java.io.File

class ReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewBinding
    private lateinit var pagesAdapter: PagesAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var session: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pagesAdapter = PagesAdapter(
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) },
            onDeleteRequested = { position -> confirmDelete(position) }
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

    private inner class PagesAdapter(
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
        private val onDeleteRequested: (Int) -> Unit
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
            private var boundPage: File? = null
            private var suppressTextChanges = false
            private var textWatcher: android.text.TextWatcher? = null

            fun bind(page: File, position: Int) {
                boundPage = page
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
