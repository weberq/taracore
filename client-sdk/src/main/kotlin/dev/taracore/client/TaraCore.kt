package dev.taracore.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.taracore.api.TaraCoreContract

/**
 * Entry point: is Tara Core here, and how do I send the user to get it?
 *
 * Every method is safe to call before the app has any permission, on any API level.
 */
object TaraCore {

    /** Package that hosts the service. */
    const val PACKAGE = TaraCoreContract.SERVICE_PACKAGE

    /** Permission a consuming app must declare. See [permissionDeclaration]. */
    const val PERMISSION = TaraCoreContract.PERMISSION

    /**
     * Whether Tara Core is installed and enabled.
     *
     * Returns false on Android 11+ if your manifest is missing the `<queries>` entry
     * -- but `:client-sdk`'s own manifest supplies it, so simply depending on this
     * library is enough.
     */
    @JvmStatic
    fun isInstalled(context: Context): Boolean = try {
        val info = context.packageManager.getApplicationInfo(PACKAGE, 0)
        info.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Version of the installed Tara Core app, or null when it is absent. */
    @JvmStatic
    fun installedVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(PACKAGE, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * An intent that takes the user to Tara Core's store listing, falling back to the
     * browser when no store app can handle it.
     *
     * Start it with `startActivity`, and only after telling the user *why* -- a
     * surprise trip to the Play Store is a hostile thing to do to someone.
     */
    @JvmStatic
    fun installIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PACKAGE"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Browser fallback for [installIntent] when no store app is installed. */
    @JvmStatic
    fun installIntentFallback(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens Tara Core itself, or null when it is not installed. */
    @JvmStatic
    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)

    /**
     * Whether this app holds the permission. A `normal` permission is granted at
     * install time, so a false here means the `<uses-permission>` line is missing
     * from the manifest rather than that the user declined something.
     */
    @JvmStatic
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The one line a consuming app must add to its own manifest. This library
     * deliberately does not add it for you -- a permission that shows up in your
     * app's details page should be a choice you made, not one a dependency made.
     */
    @JvmStatic
    val permissionDeclaration: String = """
        <uses-permission android:name="dev.taracore.permission.BIND_INFERENCE" />
    """.trimIndent()

    /**
     * The `<queries>` block. Already merged in from this library's manifest; it is
     * exposed for apps that vendor the AIDL directly instead of taking the SDK.
     */
    @JvmStatic
    val queriesDeclaration: String = """
        <queries>
            <package android:name="dev.taracore" />
        </queries>
    """.trimIndent()
}
