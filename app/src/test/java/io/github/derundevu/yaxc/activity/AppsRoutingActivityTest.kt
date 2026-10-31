package io.github.derundevu.yaxc.activity

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings.AppsRoutingMode
import io.github.derundevu.yaxc.helper.HelperSettingsTest
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.waitForAsyncState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplicationPackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppsRoutingActivityTest : HelperSettingsTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<AppsRoutingActivity>? = null

    @Before
    fun installApps() {
        install("synthetic.launcher", "Synthetic Launcher", 0)
        install("synthetic.system", "Synthetic System", ApplicationInfo.FLAG_SYSTEM)
        install("synthetic.updated", "Synthetic Updated", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun appWithoutItsOwnInternetPermissionCanBeFoundSelectedAndSaved() {
        openActivity()
        search("  SYNTHETIC LAUNCHER  ")
        compose.onNodeWithText("Synthetic Launcher").assertIsDisplayed().performClick()
        save()
        assertEquals("synthetic.launcher", settings.appsRouting)
    }

    @Test
    fun systemFilterHidesBothSystemFlagsWithoutDiscardingTheirRoutingSelection() {
        settings.appsRouting = "synthetic.system\nsynthetic.updated"
        openActivity()
        compose.onNodeWithText("Synthetic System").assertIsDisplayed()
        compose.onNodeWithText("Synthetic Updated").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.appsRoutingShowSystemApps)).performClick()
        compose.onNodeWithText("Synthetic System").assertDoesNotExist()
        compose.onNodeWithText("Synthetic Updated").assertDoesNotExist()
        compose.onNodeWithText("Synthetic Launcher").assertIsDisplayed()
        save()
        assertEquals("synthetic.system\nsynthetic.updated", settings.appsRouting)
    }

    @Test
    fun recreationRetainsSearchFilterUnsavedSelectionAndRoutingMode() {
        openActivity()
        compose.onNodeWithText("Synthetic Launcher").performClick()
        compose.onNodeWithText(app.getString(R.string.appsRoutingModeIncludeShort)).performClick()
        compose.onNodeWithText(app.getString(R.string.appsRoutingShowSystemApps)).performClick()
        search("synthetic.launcher")
        compose.runOnUiThread { controller!!.recreate() }
        awaitApps()
        compose.onNodeWithText("Synthetic Launcher").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("synthetic.launcher")
        search("synthetic.system")
        compose.onNodeWithText("Synthetic System").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.noAppsFound)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.appsRoutingShowSystemApps)).performClick()
        compose.onNodeWithText("Synthetic System").assertIsDisplayed()
        assertEquals("", settings.appsRouting)
        save()
        assertEquals("synthetic.launcher", settings.appsRouting)
        assertEquals(AppsRoutingMode.Include, settings.appsRoutingMode)
    }

    @Test
    @Config(shadows = [DelayedApplicationsPackageManager::class])
    fun savingWhileAppEnumerationIsBlockedPreservesTheEditedModeAndExistingPackages() {
        settings.appsRouting = "synthetic.system\nsynthetic.updated"
        DelayedApplicationsPackageManager.entered = CountDownLatch(1)
        DelayedApplicationsPackageManager.release = CountDownLatch(1)
        controller = Robolectric.buildActivity(AppsRoutingActivity::class.java).setup()
        try {
            compose.waitForAsyncState { DelayedApplicationsPackageManager.entered.count == 0L }
            compose.onNodeWithContentDescription(app.getString(R.string.loading)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.appsRoutingModeIncludeShort)).performClick()
            compose.onNodeWithText(app.getString(R.string.appsRoutingShowSystemApps)).performClick()
            search("synthetic")
            compose.onNodeWithContentDescription(app.getString(R.string.save)).assertIsEnabled().performClick()
            compose.waitForAsyncState { controller!!.get().isFinishing }
            assertEquals(AppsRoutingMode.Include, settings.appsRoutingMode)
            assertEquals("synthetic.system\nsynthetic.updated", settings.appsRouting)
        } finally {
            DelayedApplicationsPackageManager.release.countDown()
        }
    }

    private fun openActivity() {
        controller = Robolectric.buildActivity(AppsRoutingActivity::class.java).setup()
        awaitApps()
    }

    private fun awaitApps() {
        compose.waitForAsyncState {
            compose.onAllNodesWithText(app.getString(R.string.appsRoutingShowSystemApps))
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription(app.getString(R.string.loading))
                    .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun search(query: String) {
        compose.onNodeWithText(app.getString(R.string.search)).performTextReplacement(query)
    }

    private fun save() {
        compose.onNodeWithContentDescription(app.getString(R.string.save)).performClick()
        compose.waitForAsyncState { controller!!.get().isFinishing }
    }

    private fun install(packageName: String, label: String, systemFlags: Int) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
                flags = ApplicationInfo.FLAG_INSTALLED or systemFlags
                icon = android.R.drawable.sym_def_app_icon
            }
            // No INTERNET permission: launcher/delegating apps must not disappear from the picker.
            requestedPermissions = null
        })
    }

    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    class DelayedApplicationsPackageManager : ShadowApplicationPackageManager() {
        @Implementation
        public override fun getInstalledApplications(flags: Int): MutableList<ApplicationInfo> {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "App enumeration was not released by the test" }
            return super.getInstalledApplications(flags)
        }

        companion object {
            var entered = CountDownLatch(0)
            var release = CountDownLatch(0)
        }
    }
}
