package dev.rhineix.penumbra

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.IntentFilter

internal data class SharingOptions(
    val toPersonal: Boolean = true,
    val toWork: Boolean = true,
    val workFilesInPersonal: Boolean = true,
    val workLinksInPersonal: Boolean = true,
)
internal data class ProfileRoute(val filter: IntentFilter, val direction: Int)

/** All Penumbra-owned routes are rebuilt together so bridge repair cannot remove sharing. */
internal object CrossProfileRoutes {
    fun build(options: SharingOptions): List<ProfileRoute> = buildList {
        // Android's direction flags name the receiving profile, not the sender.
        add(ProfileRoute(IntentFilter(ProfileEngine.BRIDGE_ACTION).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT))
        fun share(direction: Int) {
            // Normal Android shares: text, images, videos, documents and multiple attachments.
            add(ProfileRoute(IntentFilter().apply {
                addAction(Intent.ACTION_SEND); addAction(Intent.ACTION_SEND_MULTIPLE)
                addCategory(Intent.CATEGORY_DEFAULT); addDataType("*/*")
            }, direction))
            // Compatibility for apps that send EXTRA_TEXT without a MIME type.
            add(ProfileRoute(IntentFilter().apply {
                addAction(Intent.ACTION_SEND); addAction(Intent.ACTION_SEND_MULTIPLE)
                addCategory(Intent.CATEGORY_DEFAULT)
            }, direction))
        }
        if (options.toPersonal) {
            share(DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)

        }
        if (options.toWork) share(DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        if (options.workFilesInPersonal) add(ProfileRoute(IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE)
            addDataType("*/*"); addDataScheme("content"); addDataScheme("file")
        }, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED))
        if (options.workLinksInPersonal) add(ProfileRoute(IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http"); addDataScheme("https")
        }, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED))
    }
}
