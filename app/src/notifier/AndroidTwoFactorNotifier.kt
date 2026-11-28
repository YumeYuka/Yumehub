package moe.yumeyuka.yumehub.notifier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import moe.yumeyuka.yumehub.MainActivity
import moe.yumeyuka.yumehub.R
import moe.yumeyuka.yumehub.data.twofactor.TwoFactorNotifier
import moe.yumeyuka.yumehub.locale.CurrentAppStrings

/** Android 侧实现：文案与通知渠道属于 UI 层，因此放在 :app 而不是 :data。 */
class AndroidTwoFactorNotifier(private val context: Context) : TwoFactorNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CurrentAppStrings.current.TwoFactor.NotificationChannel,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    override fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance !=
                NotificationManager.IMPORTANCE_NONE

    override fun showRequest(): Boolean {
        if (!canNotify()) return false
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.github_mark)
                .setContentTitle(CurrentAppStrings.current.TwoFactor.NotificationTitle)
                .setContentText(CurrentAppStrings.current.TwoFactor.NotificationBody)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // Permission may be revoked between checking and posting; the UI reports failure.
            false
        }
    }

    override fun cancel() = manager.cancel(NOTIFICATION_ID)

    private companion object {
        const val CHANNEL_ID = "two_factor_requests"
        const val NOTIFICATION_ID = 2001
    }
}
