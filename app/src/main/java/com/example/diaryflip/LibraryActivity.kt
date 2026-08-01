package com.example.diaryflip

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.diaryflip.data.SessionRepository
import com.example.diaryflip.databinding.ActivityLibraryBinding
import com.example.diaryflip.databinding.ItemLibraryDiaryBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var adapter: DiariesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DiariesAdapter(
            onOpen = { openDiary(it) },
            onRename = { promptRename(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.diariesList.layoutManager = LinearLayoutManager(this)
        binding.diariesList.adapter = adapter

        binding.newDiaryButton.setOnClickListener {
            val newSession = SessionRepository.startNewSession(this)
            Toast.makeText(this, "New diary created", Toast.LENGTH_SHORT).show()
            openDiary(newSession)
        }

        loadDiaries()
    }

    override fun onResume() {
        super.onResume()
        loadDiaries()
    }

    private fun loadDiaries() {
        val diaries = SessionRepository.listSessions(this)
        adapter.submit(diaries, SessionRepository.currentSession(this)?.absolutePath)
        if (diaries.isEmpty()) {
            binding.emptyMessage.visibility = View.VISIBLE
            binding.diariesList.visibility = View.GONE
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.diariesList.visibility = View.VISIBLE
        }
    }

    private fun openDiary(session: File) {
        SessionRepository.setCurrentSession(this, session)
        Toast.makeText(this, "Current diary changed", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION_PATH, session.absolutePath))
        finish()
    }

    private fun promptRename(session: File) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(SessionRepository.diaryLabel(session))
            setSelection(text.length)
            hint = "Diary name"
            setPadding(50, 32, 50, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename diary")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val renamed = SessionRepository.renameSession(this, session, input.text.toString())
                if (renamed == null) {
                    Toast.makeText(this, "Could not rename this diary", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Diary renamed", Toast.LENGTH_SHORT).show()
                    loadDiaries()
                }
            }
            .show()
    }

    private fun confirmDelete(session: File) {
        val pageCount = SessionRepository.pageCount(session)
        AlertDialog.Builder(this)
            .setTitle("Delete this diary?")
            .setMessage("This will permanently delete ${SessionRepository.diaryLabel(session)} and its $pageCount saved pages.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val currentWasDeleted = SessionRepository.currentSession(this)?.absolutePath == session.absolutePath
                if (SessionRepository.deleteSession(this, session)) {
                    Toast.makeText(this, "Diary deleted", Toast.LENGTH_SHORT).show()
                    loadDiaries()
                    if (currentWasDeleted) {
                        setResult(RESULT_OK)
                    }
                } else {
                    Toast.makeText(this, "Could not delete this diary", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private inner class DiariesAdapter(
        private val onOpen: (File) -> Unit,
        private val onRename: (File) -> Unit,
        private val onDelete: (File) -> Unit
    ) : RecyclerView.Adapter<DiariesAdapter.DiaryViewHolder>() {

        private val diaries = mutableListOf<File>()
        private var currentPath: String? = null

        fun submit(items: List<File>, currentPath: String?) {
            diaries.clear()
            diaries.addAll(items)
            this.currentPath = currentPath
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiaryViewHolder {
            val itemBinding = ItemLibraryDiaryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return DiaryViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: DiaryViewHolder, position: Int) {
            holder.bind(diaries[position])
        }

        override fun getItemCount(): Int = diaries.size

        inner class DiaryViewHolder(
            private val itemBinding: ItemLibraryDiaryBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(session: File) {
                val isCurrent = session.absolutePath == currentPath
                itemBinding.titleText.text = SessionRepository.diaryLabel(session)
                itemBinding.currentBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE
                itemBinding.subtitleText.text = buildSubtitle(session)
                itemBinding.openButton.text = if (isCurrent) "Selected" else "Open"
                itemBinding.openButton.isEnabled = !isCurrent
                itemBinding.openButton.setOnClickListener { onOpen(session) }
                itemBinding.renameButton.setOnClickListener { onRename(session) }
                itemBinding.deleteButton.setOnClickListener { onDelete(session) }
                itemBinding.root.setOnClickListener { onOpen(session) }
            }

            private fun buildSubtitle(session: File): String {
                val pageCount = SessionRepository.pageCount(session)
                val modified = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(session.lastModified()))
                val pageLabel = if (pageCount == 1) "1 page" else "$pageCount pages"
                return "$pageLabel • Last updated $modified"
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_PATH = "session_path"
    }
}
