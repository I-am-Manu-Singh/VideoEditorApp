package com.example.videoeditorapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.databinding.ItemIntroSlideBinding
import com.example.videoeditorapp.model.IntroSlide

class IntroAdapter(private val slides: List<IntroSlide>) :
        RecyclerView.Adapter<IntroAdapter.IntroViewHolder>() {

    inner class IntroViewHolder(private val binding: ItemIntroSlideBinding) :
            RecyclerView.ViewHolder(binding.root) {
        fun bind(slide: IntroSlide) {
            binding.tvTitle.text = slide.title
            binding.tvDescription.text = slide.description

            if (slide.lottieResId != null) {
                binding.imgSlide.visibility = View.GONE
                binding.lottieSlide.visibility = View.VISIBLE

                binding.lottieSlide.setAnimation(slide.lottieResId)
                binding.lottieSlide.repeatCount = 0

                binding.lottieSlide.setFailureListener { e ->
                    e.printStackTrace()
                    binding.lottieSlide.visibility = View.GONE
                }
                binding.lottieSlide.playAnimation()
            } else {
                binding.lottieSlide.visibility = View.GONE
                binding.imgSlide.visibility = View.VISIBLE
                binding.imgSlide.setImageResource(slide.imageResId!!)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntroViewHolder {
        val binding =
                ItemIntroSlideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return IntroViewHolder(binding)
    }

    override fun onBindViewHolder(holder: IntroViewHolder, position: Int) {
        holder.bind(slides[position])
    }

    override fun getItemCount(): Int = slides.size
}
