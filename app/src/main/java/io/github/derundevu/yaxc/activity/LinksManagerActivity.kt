package io.github.derundevu.yaxc.activity

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.helper.IntentHelper
import io.github.derundevu.yaxc.helper.LinkHelper
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.LinkViewModel
import io.github.derundevu.yaxc.viewmodel.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.reflect.cast

class LinksManagerActivity : AppCompatActivity() {

    private data class ParsedLinkSource(
        val type: Link.Type,
        val profiles: List<Profile>,
        val title: String? = null,
    )

    companion object {
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
                val loadingDialog = loadingDialog()
                loadingDialog.show()
                val detected = resolveProfiles(link)
                if (link.id == 0L) {
                    val parsed = detected.getOrNull()
                    if (parsed == null) {
                        loadingDialog.dismiss()
                        Toast.makeText(
                            this@LinksManagerActivity,
                            getString(R.string.invalidSourceContent),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    link.type = parsed.type
                    parsed.title?.let { link.name = it }
                    link.id = linkViewModel.insertAndGetId(link)
                    manageProfiles(link, emptyList(), parsed.profiles)
                } else {
                    linkViewModel.updateNow(link)
                    detected.getOrNull()?.let { parsed ->
                        var linkChanged = false
                        if (link.type != parsed.type) {
                            link.type = parsed.type
                            linkChanged = true
                        }
                        if (!parsed.title.isNullOrBlank() && link.name != parsed.title) {
                            link.name = parsed.title
                            linkChanged = true
                        }
                        if (linkChanged) linkViewModel.updateNow(link)
                        val profiles = profileViewModel.linkProfiles(link.id)
                        manageProfiles(link, profiles, parsed.profiles)
                    } ?: run {
                        Toast.makeText(
                            this@LinksManagerActivity,
                            getString(R.string.linkSavedWithoutRefresh),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                settings.lastRefreshLinks = System.currentTimeMillis()
                TProxyService.newConfig(applicationContext)
                loadingDialog.dismiss()
                setResult(RESULT_OK)
                finish()
            }
        }.show(supportFragmentManager, null)
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
            val links = linkViewModel.activeLinks()
                .filter { linkId == null || it.id == linkId }
            links.forEach { link ->
                resolveProfiles(link).getOrNull()?.let { detected ->
                    val profiles = profileViewModel.linkProfiles(link.id)
                    var linkChanged = false
                    if (link.type != detected.type) {
                        link.type = detected.type
                        linkChanged = true
                    }
                    if (!detected.title.isNullOrBlank() && link.name != detected.title) {
                        link.name = detected.title
                        linkChanged = true
                    }
                    if (linkChanged) linkViewModel.update(link)
                    val linkProfiles = profiles.filter { it.linkId == link.id }
                    manageProfiles(link, linkProfiles, detected.profiles)
                }
            }
            withContext(Dispatchers.Main) {
                settings.lastRefreshLinks = System.currentTimeMillis()
                TProxyService.newConfig(applicationContext)
                loadingDialog.dismiss()
                finish()
            }
        }
    }

    private fun jsonProfiles(link: Link, value: String): List<Profile> {
        val list = arrayListOf<Profile>()
        val trimmed = value.trim()
        val configs = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull() ?: JSONArray()
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }
                .getOrNull()
                ?.let { JSONArray().put(it) }
                ?: JSONArray()
            else -> JSONArray()
        }
        for (i in 0 until configs.length()) {
            runCatching { JSONObject::class.cast(configs[i]) }.getOrNull()?.let { configuration ->
                val label = if (configuration.has("remarks")) {
                    val remarks = configuration.getString("remarks")
                    configuration.remove("remarks")
                    remarks
                } else {
                    getString(R.string.newProfile)
                }
                val json = configuration.toString(2)
                val profile = Profile().apply {
                    linkId = link.id
                    name = label
                    config = json
                }
                list.add(profile)
            }
        }
        return list.reversed().toList()
    }

    private fun subscriptionProfiles(link: Link, value: String): List<Profile> {
        val decoded = runCatching { LinkHelper.tryDecodeBase64(value).trim() }.getOrNull() ?: ""
        val candidate = if (decoded.isNotBlank()) decoded else value.trim()
        return candidate.split("\n")
            .reversed()
            .map { LinkHelper(settings, it) }
            .filter { it.isValid() }
            .map { linkHelper ->
                val profile = Profile()
                profile.linkId = link.id
                profile.config = linkHelper.json()
                profile.name = linkHelper.remark()
                profile
            }
    }

    private fun detectProfiles(link: Link, value: String): Pair<Link.Type, List<Profile>> {
        val jsonProfiles = jsonProfiles(link, value)
        if (jsonProfiles.isNotEmpty()) {
            return Link.Type.Json to jsonProfiles
        }

        val subscriptionProfiles = subscriptionProfiles(link, value)
        if (subscriptionProfiles.isNotEmpty()) {
            return Link.Type.Subscription to subscriptionProfiles
        }

        return link.type to emptyList()
    }

    private suspend fun resolveProfiles(link: Link): Result<ParsedLinkSource> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val response = HttpHelper.fetch(
                    link = link.address,
                    userAgent = link.userAgent?.takeIf { it.isNotBlank() } ?: settings.userAgent,
                )
                val detected = detectProfiles(link, response.body.trim())
                ParsedLinkSource(
                    type = detected.first,
                    profiles = detected.second,
                    title = HttpHelper.extractSubscriptionTitle(response.headers),
                )
            }.mapCatching { detected ->
                if (detected.profiles.isEmpty()) {
                    error("No profiles detected")
                }
                detected
            }
        }
    }

    private suspend fun manageProfiles(
        link: Link, linkProfiles: List<Profile>, newProfiles: List<Profile>
    ) {
        if (newProfiles.size >= linkProfiles.size) {
            newProfiles.forEachIndexed { index, newProfile ->
                if (index >= linkProfiles.size) {
                    newProfile.linkId = link.id
                    insertProfile(newProfile)
                } else {
                    val linkProfile = linkProfiles[index]
                    updateProfile(linkProfile, newProfile)
                }
            }
            return
        }
        linkProfiles.forEachIndexed { index, linkProfile ->
            if (index >= newProfiles.size) {
                deleteProfile(linkProfile)
            } else {
                val newProfile = newProfiles[index]
                updateProfile(linkProfile, newProfile)
            }
        }
    }

    private suspend fun insertProfile(newProfile: Profile) {
        profileViewModel.create(newProfile)
    }

    private suspend fun updateProfile(linkProfile: Profile, newProfile: Profile) {
        linkProfile.name = newProfile.name
        linkProfile.config = newProfile.config
        profileViewModel.update(linkProfile)
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
