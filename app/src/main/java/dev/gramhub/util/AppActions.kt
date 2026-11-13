package dev.gramhub.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.gramhub.R

/**
 * Thin wrappers around the package-manager intents the launcher needs.
 *
 * The launcher starts these from an application context (the view model owns no
 * activity), so every intent is given FLAG_ACTIVITY_NEW_TASK before it leaves
 * this object.
 */
object AppActions {

    fun launch(context: Context, packageName: String) {
        val intent = launchIntentFor(context, packageName) ?: run {
            toast(context, R.string.launch_failed)
            return
        }
        startSafely(context, intent)
    }

    /**
     * Builds an intent that starts [packageName]'s launcher activity.
     *
     * The activity is resolved through [android.content.pm.PackageManager.queryIntentActivities]
     * and started by explicit component, the way a home-screen launcher does it.
     * resolveActivity() is not used because a launcher activity declares only
     * MAIN + LAUNCHER, without CATEGORY_DEFAULT, so resolving it can come back
     * empty even though the entry is perfectly launchable.
     */
    fun launchIntentFor(context: Context, packageName: String): Intent? {
        val probe = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)

        val activity = context.packageManager
            .queryIntentActivities(probe, 0)
            .firstNotNullOfOrNull { it.activityInfo }
            ?: return null

        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(activity.packageName, activity.name))
            .newTask()
    }

    /** Opens a web page (a repository or a release) in whatever handles it. */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .newTask()
            .addCategory(Intent.CATEGORY_BROWSABLE)
        startSafely(context, intent)
    }

    fun openAppInfo(context: Context, packageName: String) {
        startSafely(context, openAppInfoIntent(context, packageName))
    }

    fun openAppInfoIntent(context: Context, packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .newTask()

    fun requestUninstall(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE)
            .setData(Uri.fromParts("package", packageName, null))
            .newTask()
        startSafely(context, intent)
    }

    private fun Intent.newTask(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun startSafely(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(context, R.string.launch_failed)
        } catch (_: SecurityException) {
            toast(context, R.string.launch_failed)
        }
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
