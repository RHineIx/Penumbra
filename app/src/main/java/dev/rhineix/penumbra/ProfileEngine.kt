package dev.rhineix.penumbra

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast

class ProfileEngine(val context: Context) {
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    val users = context.getSystemService(UserManager::class.java)
    val admin = ComponentName(context, PenumbraAdmin::class.java)
    val prefs = Preferences(context)
    val isOwner get() = dpm.isProfileOwnerApp(context.packageName)
    val isManaged get() = users.isManagedProfile
    val canManage get() = isManaged && isOwner
    fun requireOwner() { check(canManage) { "Open Penumbra inside its managed Work profile first" } }

    fun provisionIntent(): Intent {
        check(!isManaged) { "Already inside a managed profile" }
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)) { "Managed profiles are not supported by this device" }
        check(dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            "Android cannot create another Work profile. Check for an existing Island, Shelter, or company profile in Settings."
        }
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            if(android.os.Build.VERSION.SDK_INT>=33) putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE,true)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, false)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED, false)
        }
    }

    fun finishProvisioning() {
        requireOwner()
        dpm.setProfileName(admin, prefs.text("profileName", "Penumbra"))
        configureBridge()
        dpm.setProfileEnabled(admin)
        prefs.log("Managed profile enabled")
    }
    fun configureBridge() {
        requireOwner()
        // Component overrides can survive app updates and provisioning; repair the Work endpoint.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, BridgeActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        // Clearing the DPC's filters must restore every enabled route, including sharing.
        // Android's default filters are owned by the system and are left intact.
        dpm.clearCrossProfileIntentFilters(admin)
        CrossProfileRoutes.build(sharingOptions()).forEach { route ->
            dpm.addCrossProfileIntentFilter(admin, route.filter, route.direction)
        }
    }
    internal fun sharingOptions() = SharingOptions(
        toPersonal = prefs.bool(SHARE_TO_PERSONAL, true),
        toWork = !users.hasUserRestriction(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE),
        workFilesInPersonal = prefs.bool(OPEN_WORK_FILES, true),
        workLinksInPersonal = prefs.bool(OPEN_WORK_LINKS, true),
    )
    fun setSharingToPersonalAllowed(allowed: Boolean) = setSharingOption(SHARE_TO_PERSONAL, allowed)
    fun setWorkFilesInPersonalAllowed(allowed: Boolean) = setSharingOption(OPEN_WORK_FILES, allowed)
    fun setWorkLinksInPersonalAllowed(allowed: Boolean) = setSharingOption(OPEN_WORK_LINKS, allowed)
    fun setSharingToWorkAllowed(allowed: Boolean) = restriction(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, !allowed)
    private fun setSharingOption(key: String, allowed: Boolean) {
        requireOwner()
        val previous = prefs.bool(key, true)
        prefs.set(key, allowed)
        try { configureBridge() }
        catch (e: Exception) {
            prefs.set(key, previous)
            runCatching { configureBridge() }
            throw e
        }
        prefs.log("$key=$allowed")
    }
    /** Repair old installations without resetting profile policies or data. */
    fun repairBridgeIfOwner() {
        if (canManage) configureBridge()
    }
    internal fun copyIntent(pkg: String): Intent = Intent(BRIDGE_ACTION)
        .addCategory(Intent.CATEGORY_DEFAULT).apply {
            putExtra("package", PackageRules.validate(pkg))
            WorkInstaller.attachApks(context, this, pkg)
        }
    fun sendToWork(pkg: String? = null) {
        if (pkg == null) { openWork(); return }
        check(!isManaged) { "Copy apps from the personal copy of Penumbra" }
        PackageRules.validate(pkg)
        val target = workUsers().firstOrNull()
            ?: error("No Penumbra work profile found. Set up a work profile first.")
        if (users.isQuietModeEnabled(target)) {
            deferCopy(pkg, target)
            openWork()
            Toast.makeText(context, "Turn on work, then return here to continue copying.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            context.startActivity(copyIntent(pkg))
            clearPendingCopy()
        } catch (_: ActivityNotFoundException) {
            // Older versions registered the filter in the opposite direction. Starting the Work
            // copy repairs it; retry only when the personal activity regains focus.
            deferCopy(pkg, target)
            openWork()
            Toast.makeText(context, "The work link is being repaired. Return here to continue copying.", Toast.LENGTH_LONG).show()
        }
    }
    fun workUsers(): List<UserHandle> = context.getSystemService(CrossProfileApps::class.java).targetUserProfiles
    fun openWork() {
        check(!isManaged) { "This is already the work copy of Penumbra" }
        val target = workUsers().firstOrNull()
            ?: error("No Penumbra work profile found. Set up a work profile first.")
        // Unlike requestQuietModeEnabled(), this public API does not require launcher or system
        // privileges. ActivityStartInterceptor shows Android's turn-on/unlock prompt when needed.
        context.getSystemService(CrossProfileApps::class.java).startMainActivity(
            ComponentName(context, MainActivity::class.java), target)
    }
    private fun deferCopy(pkg: String, target: UserHandle) {
        prefs.store.edit().putString(PENDING_COPY, pkg)
            .putLong(PENDING_COPY_TIME, System.currentTimeMillis())
            .putLong(PENDING_COPY_PROFILE, users.getSerialNumberForUser(target)).apply()
    }
    private fun clearPendingCopy() {
        prefs.store.edit().remove(PENDING_COPY).remove(PENDING_COPY_TIME).remove(PENDING_COPY_PROFILE).apply()
    }
    /** No stored URI grants: generate fresh grants only after returning to an active profile. */
    fun resumePendingCopy() {
        if (isManaged) return
        val pkg = prefs.text(PENDING_COPY)
        if (pkg.isBlank()) return
        val created = prefs.store.getLong(PENDING_COPY_TIME, 0)
        if (System.currentTimeMillis() - created !in 0..PENDING_COPY_MAX_AGE) { clearPendingCopy(); return }
        val serial = prefs.store.getLong(PENDING_COPY_PROFILE, -1)
        val target = workUsers().firstOrNull { users.getSerialNumberForUser(it) == serial }
        if (target == null) { clearPendingCopy(); return }
        if (users.isQuietModeEnabled(target)) return
        try {
            context.startActivity(copyIntent(pkg))
            clearPendingCopy()
        } catch (_: ActivityNotFoundException) {
            // The Work copy may still be starting. Retain the request for the next focus/resume.
        } catch (e: Exception) {
            clearPendingCopy()
            prefs.log("Pending copy failed: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(context, e.message ?: "Unable to copy this app", Toast.LENGTH_LONG).show()
        }
    }
    fun requestPause() {
        check(!isManaged) { "Pause the profile from the personal copy or Android Quick Settings" }
        val user = workUsers().firstOrNull { u ->
            runCatching { context.getSystemService(LauncherApps::class.java).getActivityList(context.packageName, u).isNotEmpty() }.getOrDefault(false)
        } ?: error("No accessible Penumbra Work profile")
        // Only the default launcher / privileged callers can perform this on many ROMs.
        users.requestQuietModeEnabled(!users.isQuietModeEnabled(user), user)
    }
    fun protectedPackages(): Set<String> = buildSet {
        add(context.packageName); add("android"); add("com.android.systemui")
        val intents = listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            Intent(Settings.ACTION_SETTINGS), Intent("android.intent.action.INSTALL_PACKAGE").setType("application/vnd.android.package-archive"))
        intents.forEach { intent -> context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach { add(it.activityInfo.packageName) } }
        context.packageManager.queryIntentServices(Intent("android.view.InputMethod"), 0).forEach { add(it.serviceInfo.packageName) }
        context.packageManager.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_PERSISTENT != 0 }.forEach { add(it.packageName) }
        add("com.google.android.gms"); add("com.google.android.gsf")
        dpm.activeAdmins?.forEach { add(it.packageName) }
        listOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.android.managedprovisioning", "com.google.android.setupwizard", "com.android.settings").forEach(::add)
    }
    fun apps(): List<AppEntry> {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS).associateBy { it.packageName }.toMutableMap()
        if (canManage) prefs.managedPackages().forEach { pkg ->
            runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull()
                ?.takeIf { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }?.let { installed[pkg] = it }
        }
        val protected = protectedPackages()
        return installed.values.filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }.map { app ->
            val hidden = canManage && runCatching { dpm.isApplicationHidden(admin, app.packageName) }.getOrDefault(false)
            AppEntry(app.packageName, app.loadLabel(pm).toString(), app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                app.flags and ApplicationInfo.FLAG_SUSPENDED != 0, hidden,
                !PackageRules.mayManage(app.packageName, context.packageName, protected))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
    private fun checkApp(pkg: String) {
        requireOwner(); PackageRules.validate(pkg)
        check(PackageRules.mayManage(pkg, context.packageName, protectedPackages())) { "This component is protected to keep the profile usable" }
    }
    fun suspend(pkg: String, value: Boolean) {
        checkApp(pkg)
        val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), value)
        check(failed.isEmpty()) { "Android refused to change suspension for ${failed.joinToString()}" }
        prefs.track(pkg); prefs.log("Suspended=$value $pkg")
    }
    fun suspendAll(packages: List<String>, value: Boolean) {
        requireOwner()
        require(packages.isNotEmpty()) { "Select at least one app" }
        val failed = mutableListOf<String>()
        packages.distinct().forEach { pkg ->
            try { suspend(pkg,value) } catch(e: Exception) { failed += "$pkg: ${e.message}" }
        }
        check(failed.isEmpty()) {
            "${packages.distinct().size - failed.size} apps updated; ${failed.size} could not be changed.\n${failed.joinToString("\n")}"
        }
    }
    fun hide(pkg: String, value: Boolean) {
        checkApp(pkg)
        check(dpm.setApplicationHidden(admin, pkg, value)) { "Android refused to change application visibility" }
        prefs.track(pkg); prefs.log("Hidden=$value $pkg")
    }
    fun restriction(key: String, enabled: Boolean) {
        requireOwner()
        check(key in RESTRICTIONS) { "Unsupported policy" }
        if (enabled) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        check(users.hasUserRestriction(key) == enabled) { "A parent policy prevents changing this setting" }
        if (key == UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE) configureBridge()
        prefs.log("Policy $key=$enabled")
    }
    fun screenshots(block: Boolean) { requireOwner(); dpm.setScreenCaptureDisabled(admin, block); prefs.log("Screen capture blocked=$block") }
    fun camera(block: Boolean) { requireOwner(); dpm.setCameraDisabled(admin,block); prefs.log("Work camera blocked=$block") }
    @Suppress("DEPRECATION")
    fun contactsSearch(block: Boolean) { requireOwner(); dpm.setCrossProfileContactsSearchDisabled(admin,block); prefs.log("Work contact search blocked=$block") }
    @Suppress("DEPRECATION")
    fun callerId(block: Boolean) { requireOwner(); dpm.setCrossProfileCallerIdDisabled(admin,block); prefs.log("Work caller ID blocked=$block") }
    fun blockUninstall(pkg: String, block: Boolean) {
        checkApp(pkg)
        dpm.setUninstallBlocked(admin,pkg,block)
        check(dpm.isUninstallBlocked(admin,pkg)==block) { "Android did not apply uninstall protection" }
        prefs.log("Uninstall blocked=$block $pkg")
    }
    fun runtimePermissions(pkg: String): List<Pair<String,Int>> {
        checkApp(pkg)
        return context.packageManager.getPackageInfo(pkg,PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES).requestedPermissions.orEmpty().mapNotNull { permission ->
            val info=runCatching { context.packageManager.getPermissionInfo(permission,0) }.getOrNull()
            if(info!=null && info.protection == PermissionInfo.PROTECTION_DANGEROUS)
                permission to dpm.getPermissionGrantState(admin,pkg,permission) else null
        }
    }
    fun permission(pkg: String, permission: String, state: Int) {
        checkApp(pkg)
        check(state in setOf(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED))
        check(runtimePermissions(pkg).any { it.first==permission }) { "Permission is not requested by this app" }
        check(dpm.setPermissionGrantState(admin,pkg,permission,state)) { "Android refused to change this permission" }
        prefs.log("Permission $permission=$state $pkg")
    }
    fun rename(name: String) {
        requireOwner(); require(name.isNotBlank() && name.length <= 40)
        dpm.setProfileName(admin, name); prefs.set("profileName", name); prefs.log("Profile renamed")
    }
    fun setWorkLock() {
        requireOwner()
        context.startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD))
    }
    fun lockWork() { requireOwner(); dpm.lockNow() }
    fun isInstalled(pkg: String): Boolean {
        PackageRules.validate(pkg)
        return try { context.packageManager.getApplicationInfo(pkg,PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        catch(_: PackageManager.NameNotFoundException) { false }
    }
    @Suppress("DEPRECATION")
    fun uninstallIntent(pkg: String): Intent {
        checkApp(pkg)
        check(isInstalled(pkg)) { "This app is no longer installed in Work. Refresh the app list." }
        check(!dpm.isUninstallBlocked(admin,pkg)) { "Turn off Protect from uninstall for this app, then try again." }
        check(!dpm.isApplicationHidden(admin,pkg)) { "Unhide this app before uninstalling it." }
        check(!users.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS)) { "An Android policy blocks uninstalling apps in this profile." }
        return Intent(Intent.ACTION_UNINSTALL_PACKAGE,android.net.Uri.parse("package:$pkg"))
            .putExtra(Intent.EXTRA_RETURN_RESULT,true)
    }
    fun completeUninstall(pkg: String): Boolean {
        requireOwner()
        if(isInstalled(pkg)) return false
        prefs.forget(pkg)
        prefs.log("Uninstalled from Work: $pkg")
        return true
    }
    fun launchIntent(pkg: String): Intent {
        requireOwner();PackageRules.validate(pkg)
        check(isInstalled(pkg)) { "This app is no longer installed in Work" }
        check(!dpm.isApplicationHidden(admin,pkg)) { "Unhide this app before opening it" }
        check(!context.packageManager.isPackageSuspended(pkg)) { "Unfreeze this app before opening it" }
        return context.packageManager.getLaunchIntentForPackage(pkg) ?: error("This app has no launcher activity. Use App info to manage it.")
    }
    fun removeWork() {
        requireOwner()
        // For a profile owner wipeData(0) removes only the managed profile.
        dpm.wipeData(0)
    }
    fun diagnostics(): String = buildString {
        appendLine("Penumbra ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}; target ${context.applicationInfo.targetSdkVersion}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Managed profile: $isManaged; profile owner: $isOwner")
        if (canManage) {
            val sharing = sharingOptions()
            appendLine("Sharing Work -> Personal: ${sharing.toPersonal}")
            appendLine("Sharing Personal -> Work: ${sharing.toWork}")
            appendLine("Open Work files in Personal: ${sharing.workFilesInPersonal}")
            appendLine("Open Work links in Personal: ${sharing.workLinksInPersonal}")
            appendLine("Block unknown sources: ${users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)}")
        }
        appendLine("\nLocal action log (may contain package names):\n${prefs.text("log")}")
    }
    companion object {
        private const val SHARE_TO_PERSONAL = "sharing.toPersonal"
        private const val OPEN_WORK_FILES = "sharing.openWorkFilesInPersonal"
        private const val OPEN_WORK_LINKS = "sharing.openWorkLinksInPersonal"
        private const val PENDING_COPY = "pendingWorkCopy"
        private const val PENDING_COPY_TIME = "pendingWorkCopyTime"
        private const val PENDING_COPY_PROFILE = "pendingWorkCopyProfile"
        private const val PENDING_COPY_MAX_AGE = 5 * 60 * 1000L
        const val BRIDGE_ACTION = "dev.rhineix.penumbra.action.WORK"
        val RESTRICTIONS = setOf(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
            UserManager.DISALLOW_BLUETOOTH_SHARING, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
    }
}
