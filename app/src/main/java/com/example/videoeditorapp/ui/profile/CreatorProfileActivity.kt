package com.example.videoeditorapp.ui.profile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityCreatorProfileBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge

class CreatorProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreatorProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatorProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }
private fun setupUI() {

    setupEditorEdgeToEdge(
        binding.appBarLayout,
        null
    )

    binding.btnBackContainer.setOnClickListener {
        finish()
    }

    val projectCount =
        com.example.videoeditorapp.utils.ProjectManager
            .listProjects(this)
            .size

    binding.tvCreatorName.text = "CREATOR_ONE"
    binding.tvCreatorTitle.text =
        "$projectCount Projects • Studio Creator"
}
}
