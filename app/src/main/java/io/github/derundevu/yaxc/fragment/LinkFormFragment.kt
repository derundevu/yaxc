package io.github.derundevu.yaxc.fragment

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.helper.HttpHelper
import java.net.URI
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LinkFormFragment(
    private val link: Link,
    private val onConfirm: () -> Unit,
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return openLink(requireActivity())
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        requireActivity().finish()
    }

    private fun openLink(context: FragmentActivity): Dialog =
        MaterialAlertDialogBuilder(context).apply {
            val layout = context.layoutInflater.inflate(
                R.layout.layout_link_form,
                LinearLayout(context)
            )

            val nameEditText = layout.findViewById<EditText>(R.id.nameEditText)
            val addressEditText = layout.findViewById<EditText>(R.id.addressEditText)
            val userAgentEditText = layout.findViewById<EditText>(R.id.userAgentEditText)
            nameEditText.setText(link.name)
            addressEditText.setText(link.address)
            userAgentEditText.setText(link.userAgent)
            val defaultLinkName = context.getString(R.string.newLink)
            var resolveTitleJob: Job? = null

            fun shouldAutofillName(): Boolean {
                val currentName = nameEditText.text?.toString()?.trim().orEmpty()
                return currentName.isEmpty() || currentName == defaultLinkName
            }

            fun scheduleResolveTitle() {
                resolveTitleJob?.cancel()
                if (!shouldAutofillName()) return

                val address = addressEditText.text?.toString()?.trim().orEmpty()
                val uri = runCatching { URI(address) }.getOrNull() ?: return
                if (uri.scheme != "https") return

                resolveTitleJob = lifecycleScope.launch {
                    delay(350)
                    val title = runCatching {
                        HttpHelper.fetch(
                            link = address,
                            userAgent = userAgentEditText.text?.toString()?.ifBlank { null },
                        ).let { response ->
                            HttpHelper.extractSubscriptionTitle(response.headers)
                        }
                    }.getOrNull()

                    if (!title.isNullOrBlank() && shouldAutofillName()) {
                        nameEditText.setText(title)
                    }
                }
            }

            addressEditText.doAfterTextChanged { scheduleResolveTitle() }
            userAgentEditText.doAfterTextChanged {
                if (shouldAutofillName()) {
                    scheduleResolveTitle()
                }
            }
            scheduleResolveTitle()

            setView(layout)
            setTitle(
                if (link.id == 0L) context.getString(R.string.newLink)
                else context.getString(R.string.editLink)
            )
            setPositiveButton(
                if (link.id == 0L) context.getString(R.string.createLink)
                else context.getString(R.string.updateLink)
            ) { _, _ ->
                val address = addressEditText.text.toString()
                val uri = runCatching { URI(address) }.getOrNull()
                val invalidLink = context.getString(R.string.invalidLink)
                val onlyHttps = context.getString(R.string.onlyHttps)
                if (uri == null) {
                    Toast.makeText(context, invalidLink, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (uri.scheme != "https") {
                    Toast.makeText(context, onlyHttps, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                link.name = nameEditText.text.toString()
                link.address = address
                link.userAgent = userAgentEditText.text.toString().ifBlank { null }
                if (link.id == 0L) {
                    link.isActive = true
                }
                onConfirm()
            }
            setNegativeButton(context.getString(R.string.closeLink)) { _, _ ->
                context.finish()
            }
        }.create()
}
