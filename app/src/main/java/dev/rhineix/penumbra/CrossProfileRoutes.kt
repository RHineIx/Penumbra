package dev.rhineix.penumbra

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.IntentFilter
import android.provider.MediaStore

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
        // These flags name who can access the handlers, not where the intent starts.
        val personalToWork = DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        val workToPersonal = DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        add(ProfileRoute(IntentFilter(ProfileEngine.BRIDGE_ACTION).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }, personalToWork))
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
        fun documents(action: String, direction: Int) {
            // DocumentsUI checks the original intent for a matching cross-profile forwarder.
            // SEND / VIEW filters alone do not allow its Personal and Work tabs.
            add(ProfileRoute(IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            }, direction))
        }
        // Registering an inlined action is safe before the photo picker exists; Android
        // simply ignores it when no activity handles it, including on older SDK extensions.
        @SuppressLint("InlinedApi")
        fun pickDocuments(direction: Int) {
            documents(Intent.ACTION_GET_CONTENT, direction)
            documents(Intent.ACTION_OPEN_DOCUMENT, direction)
            // Image-and-video requests may omit the MIME type, unlike image-only requests.
            add(ProfileRoute(IntentFilter(MediaStore.ACTION_PICK_IMAGES).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }, direction))
            add(ProfileRoute(IntentFilter(MediaStore.ACTION_PICK_IMAGES).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("image/*")
                addDataType("video/*")
            }, direction))
        }
        if (options.toPersonal) {
            share(workToPersonal)
            documents(Intent.ACTION_CREATE_DOCUMENT, workToPersonal)
            // A Personal app picking Work files returns Work content to Personal, so the
            // picker intent travels in the opposite direction to the selected content.
            pickDocuments(personalToWork)
        }
        if (options.toWork) {
            share(personalToWork)
            documents(Intent.ACTION_CREATE_DOCUMENT, personalToWork)
            pickDocuments(workToPersonal)
        }
        // Folder grants provide both read and write access, so require both directions.
        if (options.toPersonal && options.toWork) {
            for (direction in listOf(workToPersonal, personalToWork)) {
                add(ProfileRoute(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, direction))
            }
        }
        if (options.workFilesInPersonal) add(ProfileRoute(IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE)
            addDataType("*/*"); addDataScheme("content"); addDataScheme("file")
        }, workToPersonal))
        if (options.workLinksInPersonal) add(ProfileRoute(IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http"); addDataScheme("https")
        }, workToPersonal))
    }
}
