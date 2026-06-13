package com.example.videoeditorapp.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.graphics.drawable.ColorDrawable
import com.example.videoeditorapp.R
import androidx.appcompat.app.AlertDialog
import com.example.videoeditorapp.databinding.DialogBaseBinding

object AppDialog {

    fun show(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "OK",
        negativeText: String? = null,
        iconRes: Int? = null,
        cancelable: Boolean = true,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ) {

        val binding =
            DialogBaseBinding.inflate(
                LayoutInflater.from(context)
            )

        binding.tvTitle.text = title
        binding.tvMessage.text = message

        // Icon
        if (iconRes != null) {
            binding.ivDialogIcon.visibility = View.VISIBLE
            binding.ivDialogIcon.setImageResource(iconRes)
        } else {
            binding.ivDialogIcon.visibility = View.GONE
        }

        // Positive Button
        binding.btnPrimary.text = positiveText

        // Negative Button
        if (negativeText.isNullOrBlank()) {
            binding.btnSecondary.visibility = View.GONE
        } else {
            binding.btnSecondary.visibility = View.VISIBLE
            binding.btnSecondary.text = negativeText
        }

        val dialog =
            AlertDialog.Builder(context)
                .setView(binding.root)
                .setCancelable(cancelable)
                .create()

        dialog.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        binding.btnPrimary.setOnClickListener {
            dialog.dismiss()
            onPositive?.invoke()
        }

        binding.btnSecondary.setOnClickListener {
            dialog.dismiss()
            onNegative?.invoke()
        }

        dialog.show()
    }

    fun showInfo(
        context: Context,
        title: String,
        message: String,
        iconRes: Int? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveText = "OK",
            iconRes = iconRes,
            onPositive = onDismiss
        )
    }

    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "Confirm",
        negativeText: String = "Cancel",
        iconRes: Int? = null,
        onConfirm: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveText = positiveText,
            negativeText = negativeText,
            iconRes = iconRes,
            onPositive = onConfirm
        )
    }

  fun showDelete(
    context: Context,
    title: String = "Delete",
    message: String,
    iconRes: Int? = null,
    onCancel: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    show(
        context = context,
        title = title,
        message = message,
        positiveText = "Delete",
        negativeText = "Cancel",
        iconRes = iconRes,
        onPositive = onDelete,
        onNegative = onCancel
    )
}

    fun showPermission(
        context: Context,
        title: String,
        message: String,
        iconRes: Int? = null,
        onGrant: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveText = "Grant",
            negativeText = "Not Now",
            iconRes = iconRes,
            onPositive = onGrant
        )
    }
 fun showCustomView(
    context: Context,
    view: View,
    cancelable: Boolean = true
): AlertDialog {

    return AlertDialog.Builder(context)
        .setView(view)
        .setCancelable(cancelable)
        .create()
        .apply {
            show()

            window?.setBackgroundDrawable(
               ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        }
}
}