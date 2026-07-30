package com.example.diaryflip

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.diaryflip.data.SessionRepository
import com.example.diaryflip.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.endpointInput.setText(SessionRepository.endpoint(this))
        binding.tokenInput.setText(SessionRepository.token(this))
        binding.keepSpreadSwitch.isChecked = true

        binding.saveSettingsButton.setOnClickListener {
            val endpoint = binding.endpointInput.text?.toString().orEmpty()
            if (endpoint.isNotBlank() && !endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                binding.endpointInput.error = "Start the address with http:// or https://"
                return@setOnClickListener
            }
            SessionRepository.saveSettings(
                this,
                endpoint,
                binding.tokenInput.text?.toString().orEmpty(),
                true
            )
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
