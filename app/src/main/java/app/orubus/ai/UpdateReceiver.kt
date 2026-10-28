package app.orubus.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * The system's callback for the self-update session. The app process is
 * usually killed the moment the install takes, so the outcome is written
 * to prefs and the next launch reads it back (MainActivity.onResume) —
 * the "updated to vX" hello. The user-action branch just surfaces the
 * system's own confirm dialog.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val prefs = context.getSharedPreferences("orubus_updates", 0)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.wipe(context) // the new build is in — the old update artifacts are dead weight
                prefs.edit()
                    .putBoolean("justUpdated", true)
                    .putString("updatedTo", Updater.installedName())
                    .putLong("lastPrompt", 0)
                    .apply()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"
                prefs.edit().putString("updateFailed", msg).apply()
            }
        }
    }
}
