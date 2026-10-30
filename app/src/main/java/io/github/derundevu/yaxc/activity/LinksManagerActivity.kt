package io.github.derundevu.yaxc.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.fragment.LinkFormFragment
import io.github.derundevu.yaxc.helper.IntentHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackMessage
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcProgressDialog
import io.github.derundevu.yaxc.viewmodel.LinksManagerViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class LinksManagerActivity : AppCompatActivity() {
    companion object {
        private const val LINK_REF = "ref"
        private const val DELETE_ACTION = "delete"
        private const val REFRESH_LINK_ID = "refresh_link_id"
        private const val FEEDBACK_MESSAGE = "io.github.derundevu.yaxc.subscription.feedback.message"
        private const val FEEDBACK_KIND = "io.github.derundevu.yaxc.subscription.feedback.kind"
        private const val OPERATION_PENDING = "subscription_operation_pending"
        private const val FORM_TAG = "source_form"

        fun refreshLinks(context: Context): Intent = Intent(context, LinksManagerActivity::class.java)

        fun refreshLink(context: Context, linkId: Long): Intent = refreshLinks(context).apply {
            putExtra(REFRESH_LINK_ID, linkId)
        }

        fun openLink(context: Context, link: Link = Link()): Intent = refreshLinks(context).apply {
            putExtra(LINK_REF, link)
        }

        fun deleteLink(context: Context, link: Link): Intent = openLink(context, link).apply {
            putExtra(DELETE_ACTION, true)
        }

        fun readFeedback(data: Intent?): FeedbackMessage? {
            val message = data?.getStringExtra(FEEDBACK_MESSAGE)?.takeIf { it.isNotBlank() } ?: return null
            val kind = FeedbackKind.entries.firstOrNull { it.name == data.getStringExtra(FEEDBACK_KIND) }
                ?: return null
            return FeedbackMessage(message, kind)
        }
    }

    private val state: LinksManagerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        val link = IntentHelper.getParcelable(intent, LINK_REF, Link::class.java)
        val deleteAction = intent.getBooleanExtra(DELETE_ACTION, false)
        val refreshLinkId = intent.getLongExtra(REFRESH_LINK_ID, 0L).takeIf { it != 0L }
        val isForm = link != null && !deleteAction
        val firstCreation = !state.initialized
        state.initialize()

        // A retained ViewModel continues through rotation. After process death, never replay a
        // request whose transaction may already have committed before the process disappeared.
        if (firstCreation && savedInstanceState != null &&
            (!isForm || savedInstanceState.getBoolean(OPERATION_PENDING))
        ) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = state.cancel()
        })

        setContent {
            YaxcAppTheme {
                if (!isForm && state.busy) {
                    YaxcProgressDialog(
                        title = getString(if (deleteAction) R.string.feedbackDeletingSource else R.string.feedbackRefreshingSubscriptions),
                        onCancel = if (state.canCancel) ({ state.cancel() }) else null,
                    )
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.outcome.filterNotNull().collect { outcome ->
                    outcome.feedback?.let { feedback ->
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(FEEDBACK_MESSAGE, feedback.message)
                            putExtra(FEEDBACK_KIND, feedback.kind.name)
                        })
                    }
                    finish()
                }
            }
        }

        if (isForm) {
            if (supportFragmentManager.findFragmentByTag(FORM_TAG) == null) {
                LinkFormFragment.newInstance(checkNotNull(link)).show(supportFragmentManager, FORM_TAG)
            }
        } else if (firstCreation) {
            if (deleteAction) state.delete(checkNotNull(link)) else state.refresh(refreshLinkId)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(OPERATION_PENDING, state.busy || state.outcome.value != null)
        super.onSaveInstanceState(outState)
    }
}
