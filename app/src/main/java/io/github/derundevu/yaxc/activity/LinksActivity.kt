package io.github.derundevu.yaxc.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.links.LinksScreen
import io.github.derundevu.yaxc.viewmodel.LinkViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LinksActivity : AppCompatActivity() {

    private val linkViewModel: LinkViewModel by viewModels()
    private var links by mutableStateOf<List<Link>>(emptyList())
    private val feedback = YaxcFeedbackState()
    private var managerLaunched = false
    private val linksManager = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        managerLaunched = false
        LinksManagerActivity.readFeedback(result.data)?.let { feedback.show(it.message, it.kind) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        managerLaunched = savedInstanceState?.getBoolean("manager_launched") ?: false

        setContent {
            YaxcAppTheme(feedback = feedback) {
                LinksScreen(
                    links = links,
                    onBack = ::finish,
                    onRefresh = ::refreshLinks,
                    onNewLink = { openLink() },
                    onEditLink = ::openLink,
                    onDeleteLink = ::deleteLink,
                )
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                linkViewModel.links.collectLatest { links = it }
            }
        }
    }

    private fun refreshLinks() {
        val intent = LinksManagerActivity.refreshLinks(applicationContext)
        launchManager(intent)
    }

    private fun openLink(link: Link = Link()) {
        val intent = LinksManagerActivity.openLink(applicationContext, link)
        launchManager(intent)
    }

    private fun deleteLink(link: Link) {
        val intent = LinksManagerActivity.deleteLink(applicationContext, link)
        launchManager(intent)
    }

    private fun launchManager(intent: Intent) {
        if (managerLaunched) return
        managerLaunched = true
        linksManager.launch(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("manager_launched", managerLaunched)
        super.onSaveInstanceState(outState)
    }
}
