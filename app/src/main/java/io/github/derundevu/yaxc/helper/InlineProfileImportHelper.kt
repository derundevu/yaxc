package io.github.derundevu.yaxc.helper

import android.content.Context
import androidx.room.withTransaction
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.YaxcDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Clipboard and file batches append to manual groups; they never synchronize a subscription. */
object InlineProfileImportHelper {
    suspend fun append(
        context: Context,
        settings: Settings,
        parsed: SubscriptionRefreshHelper.ParsedLinkSource,
        preferredSourceId: Long?,
    ): SubscriptionRefreshHelper.RefreshResult = withContext(NonCancellable + Dispatchers.IO) {
        require(parsed.profiles.isNotEmpty()) { "No usable profiles to import" }
        val database = YaxcDatabase.ref(context.applicationContext)
        val source = database.withTransaction {
            val links = database.linkDao()
            val preferred = preferredSourceId?.let { links.find(it) }?.takeIf { it.address.isBlank() }
            val target = preferred ?: links.all().first().firstOrNull { it.address.isBlank() } ?: Link(
                name = context.getString(R.string.defaultManualGroupName), address = "", type = Link.Type.Json, isActive = true,
            ).also { it.id = links.insertAtEnd(it) }
            parsed.profiles.forEach { profile ->
                database.profileDao().create(profile.copy(id = 0L, linkId = target.id, index = -1))
            }
            target
        }
        val warnings = if (parsed.skippedProfiles.isEmpty()) emptyList() else listOf(
            SubscriptionRefreshHelper.SourceWarning(
                source.id, source.name.ifBlank { context.getString(R.string.defaultManualGroupName) }, parsed.skippedProfiles,
            ),
        )
        settings.recordSubscriptionWarnings(warnings)
        SubscriptionRefreshHelper.RefreshResult(
            attempted = 1, refreshed = 1, failed = 0,
            changes = listOf(SubscriptionRefreshHelper.SourceChanges(
                source.id, source.name, parsed.profiles.asReversed().map { it.name }, emptyList(), emptyList(),
            )),
            warnings = warnings,
        )
    }
}
