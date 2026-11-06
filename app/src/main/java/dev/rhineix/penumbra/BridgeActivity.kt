package dev.rhineix.penumbra

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Signature-protected cross-profile endpoint; installation is confirmed in the target profile. */
class BridgeActivity : EnglishActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine=ProfileEngine(this)
        if(!engine.canManage) {finish();return}
        val pkg=intent.getStringExtra("package")
        if(pkg==null) {startActivity(Intent(this,MainActivity::class.java));finish();return}
        if(runCatching {PackageRules.validate(pkg)}.isFailure) {finish();return}
        val appName=runCatching {
            packageManager.getApplicationInfo(pkg,android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(packageManager).toString()
        }.getOrDefault(pkg).take(100)
        val uris=intent.clipData?.let {clips ->(0 until clips.itemCount).mapNotNull {clips.getItemAt(it).uri}}.orEmpty()
        setContent {
            PenumbraTheme(engine.prefs) {
                var busy by remember {mutableStateOf(false)}
                var dialog by remember {mutableStateOf<AppDialog?>(null)}
                val scope=rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    dialog=AppDialog.Confirm("Copy to work profile?","A separate copy of $appName will be installed in your work profile. Your personal app data and accounts will not be copied.","Copy") {
                        if(!packageManager.canRequestPackageInstalls()) {
                            dialog=AppDialog.Confirm("Allow app installation","Enable “Allow from this source” for Penumbra in Android settings, then copy the app again from the personal profile.","Open settings") {
                                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))
                                finish()
                            }
                        } else {
                            busy=true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {WorkInstaller.install(this@BridgeActivity,pkg,uris);engine.prefs.track(pkg)}
                                    startActivity(Intent(this@BridgeActivity,MainActivity::class.java));finish()
                                } catch(e:Exception) {busy=false;dialog=AppDialog.Error(e.message ?: "Unable to copy this app")}
                            }
                        }
                    }
                }
                if(busy) Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    InfiniteProgressIndicator();Text("Preparing work installer…")
                }
                // Clear first so a confirming callback can present the next COUI dialog.
                ConfirmDialog(dialog) {dialog=null}
                LaunchedEffect(dialog,busy) {if(dialog==null && !busy) finish()}
            }
        }
    }
}
