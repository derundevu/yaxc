package io.github.derundevu.yaxc.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.links.LinksScreen
import io.github.derundevu.yaxc.viewmodel.LinkViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LinksActivity : AppCompatActivity() {

    private val linkViewModel: LinkViewModel by viewModels()
    private var links by mutableStateOf<List<Link>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YaxcAppTheme {
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
        startActivity(intent)
    }

    private fun openLink(link: Link = Link()) {
        val intent = LinksManagerActivity.openLink(applicationContext, link)
        startActivity(intent)
    }

    private fun deleteLink(link: Link) {
        val intent = LinksManagerActivity.deleteLink(applicationContext, link)
        startActivity(intent)
    }
}
