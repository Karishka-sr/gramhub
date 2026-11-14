package dev.gramhub.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gramhub.R
import dev.gramhub.data.GramHubSettings
import dev.gramhub.remote.RemoteApk
import dev.gramhub.remote.RemoteClient
import dev.gramhub.remote.RemoteFinding
import dev.gramhub.remote.RemoteScan
import dev.gramhub.remote.Severity
import dev.gramhub.remote.Verdict
import dev.gramhub.ui.components.ClientDetailSheet
import dev.gramhub.ui.theme.GramHubTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the client detail sheet and checks the two checks it offers are wired to
 * different actions.
 *
 * "Проверить" (the server's verdict) and "Проверить полностью" (the on-device
 * check) being distinct is the whole point of the sheet, so a regression that
 * collapses them into one button has to fail here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "ru-rRU-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClientDetailSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun string(id: Int): String = context.getString(id)

    private fun entry(apk: RemoteApk? = RemoteApk("app.apk", "https://x/y.apk", 1024, null, null)) =
        RemoteClient(
            id = "nekogram-nekogram",
            packageName = "tw.nekomimi.nekogram",
            label = "Nekogram",
            repo = "Nekogram/Nekogram",
            repoUrl = "https://github.com/Nekogram/Nekogram",
            description = "Third-party Telegram client",
            stars = 4015,
            latestVersion = "v13.1.1",
            latestReleaseUrl = "https://github.com/Nekogram/Nekogram/releases/tag/v13.1.1",
            apk = apk,
            scan = RemoteScan(
                verdict = Verdict.CAUTION,
                digestVerified = true,
                debugSigned = false,
                signers = listOf("aabbccddeeff0011"),
                unexpectedPermissions = listOf("android.permission.READ_SMS"),
                findings = listOf(
                    RemoteFinding(
                        check = "permissions",
                        severity = Severity.MEDIUM,
                        summary = "Unusual permission",
                        detail = "READ_SMS is not typical for a messenger",
                    ),
                ),
            ),
            archived = false,
        )

    private fun render(
        entry: RemoteClient = entry(),
        hasDownload: Boolean = false,
        scanNotice: ScanNotice? = null,
        onInstall: () -> Unit = {},
        onCheck: () -> Unit = {},
        onFullCheck: () -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            GramHubTheme(settings = GramHubSettings()) {
                ClientDetailSheet(
                    entry = entry,
                    installed = null,
                    hasDownload = hasDownload,
                    installState = null,
                    fullCheckState = null,
                    isScanning = false,
                    scanNotice = scanNotice,
                    onDismiss = {},
                    onInstall = onInstall,
                    onCancelInstall = {},
                    onCheck = onCheck,
                    onFullCheck = onFullCheck,
                    onDelete = onDelete,
                    onOpen = {},
                    onOpenRepository = {},
                    onOpenRelease = {},
                )
            }
        }
    }

    @Test
    fun `shows the label, repository and description`() {
        render()

        composeRule.onNodeWithText("Nekogram").assertExists()
        composeRule.onNodeWithText("Nekogram/Nekogram").assertExists()
        composeRule.onNodeWithText("Third-party Telegram client").assertExists()
    }

    @Test
    fun `the two checks call different actions`() {
        var checked = 0
        var fullChecked = 0
        render(onCheck = { checked++ }, onFullCheck = { fullChecked++ })

        composeRule.onNodeWithText(string(R.string.client_check)).performClick()
        composeRule.waitForIdle()
        assertTrue("the server check must fire", checked == 1)

        composeRule.onNodeWithText(string(R.string.client_full_check)).performClick()
        composeRule.waitForIdle()
        assertTrue("the on-device check must fire", fullChecked == 1)
        assertTrue("the server check must not have fired twice", checked == 1)
    }

    @Test
    fun `the delete action is hidden when nothing is downloaded`() {
        render(hasDownload = false)
        composeRule.onNodeWithText(string(R.string.client_delete)).assertDoesNotExist()
    }

    @Test
    fun `the delete action appears once a file is on disk`() {
        render(hasDownload = true)
        composeRule.onNodeWithText(string(R.string.client_delete)).assertExists()
    }

    @Test
    fun `install is disabled when the entry has no APK`() {
        render(entry = entry(apk = null))
        composeRule.onNodeWithText(string(R.string.install)).assertIsNotEnabled()
    }

    @Test
    fun `the server findings are listed inline`() {
        render()
        composeRule.onNodeWithText("Unusual permission").assertExists()
        composeRule.onNodeWithText("READ_SMS is not typical for a messenger").assertExists()
    }

    @Test
    fun `an unsupported check explains itself inside the sheet`() {
        render(scanNotice = ScanNotice("nekogram-nekogram", ScanMessage.UNSUPPORTED))

        composeRule.onNodeWithText(string(R.string.client_check_unsupported)).assertExists()
    }

    @Test
    fun `a failed check explains itself inside the sheet`() {
        render(scanNotice = ScanNotice("nekogram-nekogram", ScanMessage.FAILED))

        composeRule.onNodeWithText(string(R.string.client_check_failed)).assertExists()
    }

    @Test
    fun `an explanation for another entry is not shown`() {
        render(scanNotice = ScanNotice("someone-else", ScanMessage.UNSUPPORTED))

        composeRule.onNodeWithText(string(R.string.client_check_unsupported)).assertDoesNotExist()
    }

    @Test
    fun `no explanation is shown before a check runs`() {
        render()

        composeRule.onNodeWithText(string(R.string.client_check_unsupported)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.client_check_failed)).assertDoesNotExist()
    }
}
