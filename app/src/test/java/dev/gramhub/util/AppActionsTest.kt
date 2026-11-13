package dev.gramhub.util

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The launcher starts intents from an application context, which the platform
 * rejects unless FLAG_ACTIVITY_NEW_TASK is set. These tests pin that down so the
 * "startActivity from outside of an Activity" crash cannot come back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppActionsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Teaches the shadow package manager that [packageName] has a launcher entry. */
    private fun registerLauncherActivity(packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.MainActivity"
            }
        }
        val probe = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        shadowOf(context.packageManager).addResolveInfoForIntent(probe, resolveInfo)
    }

    @Test
    fun `launch intent carries NEW_TASK and targets the requested package`() {
        val packageName = "org.telegram.messenger"
        registerLauncherActivity(packageName)

        val intent = AppActions.launchIntentFor(context, packageName)

        assertNotNull(intent)
        // Started by explicit component, exactly like a home-screen launcher.
        assertEquals(packageName, intent!!.component?.packageName)
        assertEquals("$packageName.MainActivity", intent.component?.className)
        assertTrue(
            "launch intent must be startable from an application context",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(Intent.ACTION_MAIN, intent.action)
    }

    @Test
    fun `launch intent is null for a package that has no launcher entry`() {
        assertEquals(null, AppActions.launchIntentFor(context, "com.example.not.installed"))
    }

    @Test
    fun `app info intent carries NEW_TASK and points at the package`() {
        val intent = AppActions.openAppInfoIntent(context, "tw.nekomimi.nekogram")

        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("package:tw.nekomimi.nekogram", intent.dataString)
    }
}
