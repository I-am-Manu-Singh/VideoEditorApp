package com.example.videoeditorapp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityOtpBinding

class OtpVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOtpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val email = intent.getStringExtra("EMAIL") ?: "your email"
        binding.tvSubtitle.text = "We've sent a 4-digit code to $email. Enter it below to verify."

        setupOtpAutoAdvance()
        setupListeners()
    }

    private fun setupOtpAutoAdvance() {
        val edits = listOf(binding.etOtp1, binding.etOtp2, binding.etOtp3, binding.etOtp4)
        edits.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && index < edits.size - 1) {
                        edits[index + 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
    }

    private fun setupListeners() {
        binding.btnVerify.setOnClickListener {
            val otp = "${binding.etOtp1.text}${binding.etOtp2.text}${binding.etOtp3.text}${binding.etOtp4.text}"
            if (otp.length == 4) {
                getSharedPreferences("VideoEditorPrefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("IS_LOGGED_IN", true)
                    .apply()

                Toast.makeText(this, "Success! Account verified.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java))
                finishAffinity()
            } else {
                Toast.makeText(this, "Please enter full 4-digit code", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvResend.setOnClickListener {
            Toast.makeText(this, "Code resent successfully", Toast.LENGTH_SHORT).show()
        }
    }
}
