package dev.rhineix.penumbra

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Preferences(context: Context) {
    val store = context.getSharedPreferences("penumbra", Context.MODE_PRIVATE)
    init {
        // Upgrade to the current settings schema while retaining profile and appearance choices.
        if (store.getInt("settingsSchema", 0) < 2) {
            val retained = setOf("language", "theme", "accent", "accent_v2", "navigationBlur", "captureGuard",
                "showSystem", "profileName", "managedPackages", "pendingWorkCopy", "pendingWorkCopyTime",
                "pendingWorkCopyProfile", "sharing.toPersonal", "sharing.openWorkFilesInPersonal",
                "sharing.openWorkLinksInPersonal")
            store.edit().apply {
                store.all.keys.filter { it !in retained }.forEach(::remove)
                putInt("settingsSchema", 2)
            }.apply()
        }
    }
    fun bool(key: String, default: Boolean = false) = store.getBoolean(key, default)
    fun text(key: String, default: String = "") = store.getString(key, default) ?: default
    fun set(key: String, value: Boolean) { store.edit().putBoolean(key, value).apply() }
    fun set(key: String, value: String) { store.edit().putString(key, value).apply() }
    fun managedPackages(): Set<String> = store.getStringSet("managedPackages", emptySet())!!.toSet()
    fun track(pkg: String) { store.edit().putStringSet("managedPackages", managedPackages() + pkg).apply() }
    fun forget(pkg: String) { store.edit().putStringSet("managedPackages", managedPackages() - pkg).apply() }
    @Synchronized fun log(message: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        set("log", ("$time  $message\n" + text("log")).take(16000))
    }
}
