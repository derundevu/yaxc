package io.github.derundevu.yaxc.activity

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.fragment.LinkFormFragment
import io.github.derundevu.yaxc.helper.IntentHelper
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.LinkViewModel
import io.github.derundevu.yaxc.viewmodel.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LinksManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LinksManagerActivity"
        private const val LINK_REF = "ref"
        private const val DELETE_ACTION = "delete"
        private const val REFRESH_LINK_ID = "refresh_link_id"

        fun refreshLinks(context: Context): Intent {
            return Intent(context, LinksManagerActivity::class.java)
        }

        fun refreshLink(context: Context, linkId: Long): Intent {
            return Intent(context, LinksManagerActivity::class.java).apply {
                putExtra(REFRESH_LINK_ID, linkId)
            }
        }

        fun openLink(context: Context, link: Link = Link()): Intent {
            return Intent(context, LinksManagerActivity::class.java).apply {
                putExtra(LINK_REF, link)
            }
        }

        fun deleteLink(context: Context, link: Link): Intent {
            return Intent(context, LinksManagerActivity::class.java).apply {
                putExtra(LINK_REF, link)
                putExtra(DELETE_ACTION, true)
            }
        }
    }

    private val settings by lazy { Settings(applicationContext) }
    private val subscriptionRefreshHelper by lazy {
        SubscriptionRefreshHelper(applicationContext, settings)
    }
    private val linkViewModel: LinkViewModel by viewModels()
    private val profileViewModel: ProfileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val link: Link? = IntentHelper.getParcelable(intent, LINK_REF, Link::class.java)
        val deleteAction = intent.getBooleanExtra(DELETE_ACTION, false)
        val refreshLinkId = intent.getLongExtra(REFRESH_LINK_ID, 0L)

        if (link == null) {
            refreshLinks(refreshLinkId.takeIf { it != 0L })
            return
        }

        if (deleteAction) {
            deleteLink(link)
            return
        }

        LinkFormFragment(link) {
            lifecycleScope.launch {
                if (link.address.isBlank()) {
                    if (link.name.isBlank()) {
                        link.name = getString(R.string.newSource)
                    }
                    link.subscriptionMetadata = null
                    if (link.id == 0L) {
                        link.id = linkViewModel.insertAndGetId(link)
                    } else {
                        linkViewModel.updateNow(link)
                    }
                    setResult(RESULT_OK)
                    finish()
                    return@launch
                }

                saveRemoteLink(link)
            }
        }.show(supportFragmentManager, null)
    }

    private suspend fun saveRemoteLink(link: Link) {
        val loadingDialog = loadingDialog()
        loadingDialog.show()

        val result = runCatching {
            val detected = runCatching { subscriptionRefreshHelper.resolveProfiles(link) }

            if (link.id == 0L) {
                val parsed = detected.getOrThrow()
                subscriptionRefreshHelper.saveResolvedLink(link, parsed)
            } else {
                linkViewModel.updateNow(link)
                detected.getOrNull()?.let { parsed ->
                    subscriptionRefreshHelper.saveResolvedLink(link, parsed)
                } ?: run {
                    Log.w(TAG, "Saved source without refresh", detected.exceptionOrNull())
                    Toast.makeText(
                        this@LinksManagerActivity,
                        getString(R.string.linkSavedWithoutRefresh),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        if (loadingDialog.isShowing) {
            loadingDialog.dismiss()
        }

        result.onSuccess {
            if (TProxyService.isActive()) {
                runCatching {
                    TProxyService.newConfig(applicationContext)
                }.onFailure { error ->
                    Log.w(TAG, "Could not reload runtime after saving source", error)
                }
            }
            setResult(RESULT_OK)
            finish()
        }.onFailure { error ->
            Log.w(TAG, "Could not parse or save source: ${link.address}", error)
            Toast.makeText(
                this@LinksManagerActivity,
                getString(R.string.invalidSourceContent),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private fun loadingDialog(): Dialog {
        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.loading_dialog,
            LinearLayout(this)
        )
        return MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
    }

    private fun refreshLinks(linkId: Long? = null) {
        val loadingDialog = loadingDialog()
        loadingDialog.show()
        lifecycleScope.launch {
            val result = runCatching {
                subscriptionRefreshHelper.refreshLinks(linkId)
            }
            withContext(Dispatchers.Main) {
                loadingDialog.dismiss()
                result.exceptionOrNull()?.let { error ->
                    Log.w(TAG, "Could not refresh sources", error)
                    Toast.makeText(
                        this@LinksManagerActivity,
                        getString(R.string.invalidSourceContent),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                finish()
            }
        }
    }

    private suspend fun deleteProfile(linkProfile: Profile) {
        profileViewModel.remove(linkProfile)
        withContext(Dispatchers.Main) {
            val selectedProfile = settings.selectedProfile
            if (selectedProfile == linkProfile.id) {
                settings.selectedProfile = 0L
            }
        }
    }

    private fun deleteLink(link: Link) {
        lifecycleScope.launch {
            profileViewModel.linkProfiles(link.id)
                .forEach { linkProfile ->
                    deleteProfile(linkProfile)
                }
            linkViewModel.delete(link)
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }
}
