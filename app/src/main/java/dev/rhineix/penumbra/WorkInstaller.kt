package dev.rhineix.penumbra

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

object WorkInstaller {
    fun attachApks(context: Context, intent: Intent, pkg: String) {
        PackageRules.validate(pkg)
        val app = context.packageManager.getApplicationInfo(pkg, 0)
        val count = 1 + app.splitPublicSourceDirs.orEmpty().size
        val clips = ClipData.newRawUri("Penumbra APK", Uri.parse("content://${context.packageName}.transfer/apk/$pkg/0"))
        for (index in 1 until count) clips.addItem(ClipData.Item(Uri.parse("content://${context.packageName}.transfer/apk/$pkg/$index")))
        intent.clipData = clips
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun install(context: Context, pkg: String, uris: List<Uri>) {
        ProfileEngine(context).requireOwner()
        require(uris.isNotEmpty() && uris.size <= 256) { "Missing APK files; retry from the personal copy" }
        uris.forEach { uri ->
            require(uri.scheme == "content" && uri.authority?.substringAfter('@') == "${context.packageName}.transfer" && uri.pathSegments.take(2) == listOf("apk",pkg)) { "Unexpected APK source" }
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(PackageRules.validate(pkg))
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                uris.forEachIndexed { index, uri ->
                    val name=if(index==0) "base.apk" else "split_$index.apk"
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        session.openWrite(name,0,-1).use { output -> input.copyTo(output);session.fsync(output) }
                    } ?: error("Cannot read APK split $index")
                }
                val callback = Intent(context, InstallResultActivity::class.java).putExtra("sessionId",id)
                val options = if(Build.VERSION.SDK_INT>=35) ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(if(Build.VERSION.SDK_INT>=36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle() else null
                // Explicit mutable PendingIntent: PackageInstaller fills status extras.
                val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getActivity(context,id,callback,flags,options).intentSender
                session.commit(sender)
            }
        } catch(e:Exception) { installer.abandonSession(id); throw e }
    }
}

class InstallResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val consent=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if(consent!=null) startActivity(consent)
            else Toast.makeText(this,"Installer returned no consent activity",Toast.LENGTH_LONG).show()
        } else {
            val success=status==PackageInstaller.STATUS_SUCCESS
            val message=if(success) "App copied to work profile" else intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed ($status)"
            Preferences(this).log("Installer: $message")
            Toast.makeText(this,message,Toast.LENGTH_LONG).show()
            startActivity(Intent(this,MainActivity::class.java))
        }
        finish()
    }
}
