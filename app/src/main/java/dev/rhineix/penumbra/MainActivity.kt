package dev.rhineix.penumbra

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.UserManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.*
import io.github.suqi8.coui.kmp.window.WindowBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

abstract class EnglishActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        val configuration=Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en")); setLayoutDirection(Locale.ENGLISH)
        }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=33) {
            val manager=getSystemService(LocaleManager::class.java)
            if(manager.applicationLocales.toLanguageTags()!="en") manager.applicationLocales=LocaleList.forLanguageTags("en")
        }
        Preferences(this).set("language","en")
        if(Preferences(this).bool("captureGuard")) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
    }
}
class MainActivity : EnglishActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val managed=getSystemService(UserManager::class.java).isManagedProfile
        packageManager.setComponentEnabledSetting(ComponentName(this,BridgeActivity::class.java),
            if(managed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        runCatching { ProfileEngine(this).repairBridgeIfOwner() }
            .onFailure { Preferences(this).log("Bridge repair: ${it.message}") }
        setContent { PenumbraApp(this) }
    }
    override fun onPostResume() {
        super.onPostResume()
        ProfileEngine(this).resumePendingCopy()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ProfileEngine(this).resumePendingCopy()
    }
}

@Composable internal fun PenumbraTheme(prefs: Preferences, theme: String=prefs.text("theme","system"), accent: String=prefs.text("accent_v2","blue"), content: @Composable () -> Unit) {
    val dark=theme=="dark" || theme=="system" && isSystemInDarkTheme()
    val seed=when(accent) {"violet" -> if(dark) Color(0xFFB9A2FF) else Color(0xFF7045CD);"green" -> if(dark) Color(0xFF74D5B3) else Color(0xFF137452);else -> if(dark) Color(0xFFA7C7FF) else Color(0xFF2463DE)}
    val colors=if(dark) darkColorScheme(primary=seed,onPrimary=Color(0xFF102033),background=Color(0xFF111315),surface=Color(0xFF1C1F23),onSurfaceContainer=seed)
        else lightColorScheme(primary=seed,background=Color(0xFFF3F5F8),surface=Color.White,onSurfaceContainer=seed)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { COUITheme(colors=colors,content=content) }
}

@Composable private fun PenumbraApp(activity: MainActivity) {
    val state=remember {UiState(activity)}
    val engine=state.engine
    val scope=rememberCoroutineScope()
    val snackbars=remember {SnackbarHostState()}
    val haze=rememberHazeState()
    val lifecycle=LocalLifecycleOwner.current
    var pendingUninstall by rememberSaveable {mutableStateOf<String?>(null)}
    val uninstall=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pkg=pendingUninstall
        pendingUninstall=null
        if(pkg!=null) {
            var removed=false
            state.execute(scope,success="",onSuccess={
                if(removed) state.notice="App uninstalled from Work"
                else if(result.resultCode==android.app.Activity.RESULT_CANCELED) state.notice="Uninstall cancelled or blocked by Android. Check App info for details."
                else state.error(IllegalStateException("Android did not remove this app. Check uninstall protection, active device administrators and the app’s system status in App info."))
            }) { removed=engine.completeUninstall(pkg) }
        } else state.revision++
    }
    val provisioning=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        state.revision++
        if(result.resultCode==android.app.Activity.RESULT_OK) state.notice="Setup returned successfully. Open the work-badged copy of Penumbra."
        else state.notice="Work profile setup was cancelled or could not finish."
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null) state.execute(scope,"Diagnostics exported") {
            activity.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(engine.diagnostics())} ?: error("Cannot open destination")
        }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) state.revision++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(state.revision) {
        state.hasWork=withContext(Dispatchers.IO) {
            !engine.isManaged && engine.workUsers().any { user -> runCatching {activity.getSystemService(LauncherApps::class.java).getActivityList(activity.packageName,user).isNotEmpty()}.getOrDefault(false) }
        }
    }
    LaunchedEffect(state.revision,state.page) {
        if(state.page==Page.Apps) {
            state.loadingApps=true
            try {state.apps=withContext(Dispatchers.IO) {engine.apps()}}
            catch(e: Exception) {state.error(e)}
            finally {state.loadingApps=false}
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { text -> snackbars.showSnackbar(text,withDismissAction=true); if(state.notice==text) state.notice=null }
    }
    LaunchedEffect(state.captureGuard) {
        if(state.captureGuard) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    BackHandler(!state.mainPage) {state.back()}
    PenumbraTheme(state.prefs,state.theme,state.accent) {
        val colors=COUITheme.colorScheme
        val dark=colors.background.red<.2f
        LaunchedEffect(dark) {
            WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }
        }
        val blurStyle=remember(colors.surface) {HazeBlurStyle {
            blurRadius(24.dp);backgroundColor(colors.surface)
            colorEffects(listOf(HazeColorEffect.tint(colors.surface.copy(alpha=.82f))));noiseFactor(.015f)
        }}
        Scaffold(containerColor=colors.background,
            topBar={
                Column {
                    SmallTopAppBar(
                        title=if(state.selecting) "${state.selected.size} selected" else state.page.title,
                        color=colors.background,
                        navigationIcon={
                            if(!state.mainPage) ToolbarAction(TablerIcons.ArrowLeft,"Back") {state.back()}
                            else if(state.selecting) ToolbarAction(TablerIcons.X,"Cancel selection") {state.selecting=false;state.selected=emptySet()}
                        },
                        actions={
                            if(state.page==Page.Apps) {
                                if(engine.canManage) ToolbarAction(if(state.selecting) TablerIcons.Check else TablerIcons.ListCheck,"Select apps",!state.busy) {state.selecting=!state.selecting;state.selected=emptySet()}
                                ToolbarAction(TablerIcons.Filter,"Filter apps",!state.busy) {state.sheet=AppSheet.Filters}
                                ToolbarAction(TablerIcons.Refresh,"Refresh apps",!state.busy && !state.loadingApps) {state.revision++}
                            } else if(state.page==Page.Work) {
                                Text(if(engine.canManage) "Work" else "Personal",fontSize=12.sp,color=colors.primary,modifier=Modifier.padding(end=20.dp))
                            }
                        },showDivider=false)
                    if(state.busy) LinearProgressIndicator()
                }
            },
            bottomBar={if(state.mainPage) {
                Box(if(state.navigationBlur) Modifier.hazeBlur(HazeInput.Sources(haze),blurStyle) else Modifier.background(colors.surface)) {
                    NavigationBar(color=Color.Transparent,showDivider=true) {
                        NavigationBarItem(state.page==Page.Work,{state.navigate(Page.Work)},TablerIcons.Briefcase,"Work")
                        NavigationBarItem(state.page==Page.Apps,{state.navigate(Page.Apps)},TablerIcons.Apps,"Apps")
                        NavigationBarItem(state.page==Page.Settings,{state.navigate(Page.Settings)},TablerIcons.Settings,"Settings")
                    }
                }
            }},
            snackbarHost={SnackbarHost(snackbars)},
        ) { padding ->
            val filtered=state.apps.filter {
                (state.showSystem || !it.system) && (state.search.isBlank() || it.label.contains(state.search,true) || it.packageName.contains(state.search,true)) && when(state.filter) {1 -> !it.suspended && !it.hidden;2 -> it.suspended;3 -> it.hidden;else -> true}
            }
            LazyColumn(
                modifier=Modifier.fillMaxSize().then(if(state.navigationBlur && state.mainPage) Modifier.hazeSource(haze) else Modifier),
                contentPadding=PaddingValues(start=16.dp,end=16.dp,top=padding.calculateTopPadding()+12.dp,bottom=padding.calculateBottomPadding()+24.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp),
            ) {
                when(state.page) {
                    Page.Work -> {
                        item {
                            SettingsGroup {
                                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                        Icon(TablerIcons.Briefcase,null,Modifier.size(40.dp),tint=colors.primary)
                                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                            Text("Work profile",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                                            Text(when {engine.canManage -> "Active · Managed by Penumbra";state.hasWork -> "Ready on this device";engine.isManaged -> "Managed by another app";else -> "Not set up"},fontSize=14.sp,color=colors.onSurfaceSecondary)
                                        }
                                    }
                                    Text(if(engine.canManage) "Manage your work apps and the information shared between profiles." else "Keep a separate copy of your apps, accounts, and app data on this phone.",fontSize=14.sp,lineHeight=21.sp,color=colors.onSurfaceSecondary)
                                    Button(onClick={
                                        when {engine.canManage -> state.navigate(Page.Apps);state.hasWork -> state.launch {engine.openWork()};else -> state.confirm("Set up work profile?","Android will ask you to approve a work profile managed by Penumbra. An existing work profile may prevent setup.","Set up") {state.launch {provisioning.launch(engine.provisionIntent())}}}
                                    },modifier=Modifier.fillMaxWidth(),enabled=!state.busy && (!engine.isManaged || engine.canManage),colors=ButtonDefaults.buttonColorsPrimary()) {
                                        Text(when {engine.canManage -> "Manage apps";state.hasWork -> "Open work profile";else -> "Set up work profile"})
                                    }
                                }
                            }
                        }
                        item {SectionTitle("Manage")
                            SettingsGroup {
                                SettingsRow("Apps","Copy and manage apps",TablerIcons.Apps) {state.navigate(Page.Apps)}
                                SettingsRow("Privacy controls","Sharing, camera, and screen capture",TablerIcons.ShieldLock) {state.navigate(Page.Privacy)}
                                if(!engine.isManaged) SettingsRow("Open work copy","Continue in the work profile",TablerIcons.ExternalLink,!state.busy) {state.launch {engine.openWork()}}
                            }
                        }
                        item {SectionTitle("Profile settings")
                            SettingsGroup {
                                if(engine.canManage) {
                                    SettingsRow("Profile name",state.prefs.text("profileName","Penumbra"),TablerIcons.Briefcase,!state.busy) {state.dialog=AppDialog.Input("Profile name","Name",state.prefs.text("profileName","Penumbra")) {name ->state.execute(scope) {engine.rename(name)}}}
                                    SettingsRow("Work profile lock","Set a password or PIN",TablerIcons.Lock,!state.busy) {state.launch {engine.setWorkLock()}}
                                    SettingsRow("Lock now","Android may also lock the phone",TablerIcons.LockOpen,!state.busy) {state.confirm("Lock now?","Depending on your lock settings, Android may lock the entire phone.","Lock") {state.launch {engine.lockWork()}}}
                                } else SettingsRow("Pause or resume work","Also available in Android Quick Settings",TablerIcons.PlayerPause,!state.busy) {
                                    state.launch {try {engine.requestPause()} catch(_: SecurityException) {state.notice="Use the Work apps toggle in Android Quick Settings or your launcher.";activity.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))}}
                                }
                                SettingsRow("Android account settings",null,TablerIcons.DeviceMobile,!state.busy) {state.launch {activity.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))}}
                            }
                        }
                        if(engine.canManage) item {
                            SectionTitle("Remove profile")
                            SettingsGroup {BasicComponent(title="Delete work profile",summary="Permanently erase work apps and data",startAction={AppIcon(TablerIcons.Trash,color=colors.error)},titleColor=BasicComponentDefaults.titleColor(color=colors.error),enabled=!state.busy,onClick={
                                state.confirm("Delete work profile?","All apps, accounts, and data in this work profile will be permanently erased. Personal data is not deleted. Export important work data first.","Delete profile",danger=true,erase=true) {state.launch {engine.removeWork()}}
                            })}
                        }
                        item {Hint("Work is an Android profile linked to your primary user. App data stays separate between the two profiles.")}
                    }
                    Page.Apps -> {
                        item {TextField(state.search,{state.search=it},label="Search apps",backgroundMode=TextFieldMode.Rectangle,singleLine=true,showClearButton=true,leadingIcon={AppIcon(TablerIcons.Search)},modifier=Modifier.fillMaxWidth())}
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(if(state.loadingApps) "Loading apps…" else "${filtered.size} apps"+when(state.filter) {1 -> " · Active";2 -> " · Frozen";3 -> " · Hidden";else -> ""},fontSize=13.sp,color=colors.onSurfaceSecondary,modifier=Modifier.weight(1f))
                                if(state.selecting) {
                                    ToolbarAction(TablerIcons.Snowflake,"Freeze selected apps",state.selected.isNotEmpty() && !state.busy) {
                                        val targets=state.selected.toList()
                                        state.confirm("Freeze ${targets.size} apps?","Frozen apps cannot run or send notifications until you unfreeze them.","Freeze") {state.execute(scope,"Apps frozen") {engine.suspendAll(targets,true)};state.selected=emptySet();state.selecting=false}
                                    }
                                    ToolbarAction(TablerIcons.PlayerPlay,"Unfreeze selected apps",state.selected.isNotEmpty() && !state.busy) {
                                        val targets=state.selected.toList();state.execute(scope,"Apps unfrozen") {engine.suspendAll(targets,false)};state.selected=emptySet();state.selecting=false
                                    }
                                }
                            }
                        }
                        if(state.loadingApps && state.apps.isEmpty()) item {Box(Modifier.fillMaxWidth().padding(40.dp),contentAlignment=Alignment.Center) {InfiniteProgressIndicator()}}
                        if(!state.loadingApps && filtered.isEmpty()) item {ScreenEmpty(TablerIcons.Search,"No apps found","Try a different search or include system apps in the filter.")}
                        items(filtered,key={it.packageName}) {app ->
                            Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(0.dp),cornerRadius=16.dp,colors=CardDefaults.defaultColors(color=colors.surface,contentColor=colors.onSurface)) {
                                BasicComponent(
                                    startAction={PackageIcon(activity,app.packageName)},
                                    endActions={if(state.selecting && !app.protected) Checkbox(state=if(app.packageName in state.selected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,onClick={state.toggleSelection(app.packageName)},enabled=!state.busy)
                                        else AppIcon(TablerIcons.DotsVertical,"App actions")},
                                    onClick={if(state.selecting && !app.protected) state.toggleSelection(app.packageName) else state.sheet=AppSheet.Actions(app)},enabled=!state.busy) {
                                    Text(app.label,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Text(when {app.hidden -> "Hidden · ${app.packageName}";app.suspended -> "Frozen · ${app.packageName}";app.protected -> "Protected · ${app.packageName}";else -> app.packageName},fontSize=12.sp,color=colors.onSurfaceSecondary,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                                }
                            }
                        }
                        if(!engine.canManage) item {Hint("Choose an app to copy it into your Penumbra work profile. App files are copied; app data and accounts are not.")}
                    }
                    Page.Settings -> {
                        item {SectionTitle("Preferences")
                            SettingsGroup {
                                SettingsRow("Appearance","Theme, accent color, and blur",TablerIcons.Palette) {state.navigate(Page.Appearance)}
                                SettingsRow("Privacy controls","Work profile policies",TablerIcons.ShieldLock) {state.navigate(Page.Privacy)}
                            }
                        }
                        item {SectionTitle("Tools")
                            SettingsGroup {
                                SettingsRow("Diagnostics","Export logs and device information",TablerIcons.FileSearch) {state.navigate(Page.Diagnostics)}
                            }
                        }
                        item {SectionTitle("About")
                            SettingsGroup {SettingsRow("Penumbra","Version ${BuildConfig.VERSION_NAME}",TablerIcons.InfoCircle) {state.navigate(Page.About)}}
                        }
                    }
                    Page.Privacy -> {
                        if(!engine.canManage) item {SettingsGroup {
                            SettingsRow("Open the work copy","These controls are applied by the work-badged copy of Penumbra.",TablerIcons.Briefcase,!state.busy) {state.launch {engine.openWork()}}
                        }}
                        item {SectionTitle("Sharing between profiles")
                            SettingsGroup {
                                val sharing=remember(state.revision) {engine.sharingOptions()}
                                SettingsToggle("Allow sharing to personal","Share work photos, files and text with personal apps",TablerIcons.Share,sharing.toPersonal,engine.canManage && !state.busy) {state.execute(scope) {engine.setSharingToPersonalAllowed(it)}}
                                SettingsToggle("Allow sharing to work","Share personal photos, files and text with work apps",TablerIcons.Copy,sharing.toWork,engine.canManage && !state.busy) {state.execute(scope) {engine.setSharingToWorkAllowed(it)}}
                                SettingsToggle("Open work files in personal apps","Include personal apps when opening a work file",TablerIcons.File,sharing.workFilesInPersonal,engine.canManage && !state.busy) {state.execute(scope) {engine.setWorkFilesInPersonalAllowed(it)}}
                                SettingsToggle("Open work links in personal apps","Include personal browsers and apps for work web links",TablerIcons.World,sharing.workLinksInPersonal,engine.canManage && !state.busy) {state.execute(scope) {engine.setWorkLinksInPersonalAllowed(it)}}
                            }
                            Hint("Choose the destination in Android’s share or open-with menu. Clipboard restrictions are controlled separately below.")
                        }
                        item {SectionTitle("Clipboard & installation")
                            SettingsGroup {
                                val policies=listOf(
                                    Triple(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,"Block outgoing clipboard","Prevent copying work content into personal apps"),
                                    Triple(UserManager.DISALLOW_BLUETOOTH_SHARING,"Block Bluetooth sharing","Prevent work apps from sending files over Bluetooth"),
                                    Triple(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,"Block unknown sources","Also blocks copying apps from Personal to Work"))
                                val icons=listOf(TablerIcons.Clipboard,TablerIcons.Bluetooth,TablerIcons.Package)
                                policies.forEachIndexed {index,(key,title,summary) ->
                                    val checked=remember(state.revision,key) {engine.users.hasUserRestriction(key)}
                                    SettingsToggle(title,summary,icons[index],checked,engine.canManage && !state.busy) { enabled ->
                                        if(key==UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES && enabled) {
                                            state.confirm("Block unknown sources?","This also blocks copying apps from Personal to Work, even if Android’s installation-source permission is enabled. Installed apps are not removed.","Block") {state.execute(scope) {engine.restriction(key,true)}}
                                        } else state.execute(scope) {engine.restriction(key,enabled)}
                                    }
                                }
                            }
                        }
                        item {SectionTitle("Camera & screen")
                            SettingsGroup {
                                val screenshots=remember(state.revision) {runCatching {engine.dpm.getScreenCaptureDisabled(engine.admin)}.getOrDefault(false)}
                                val camera=remember(state.revision) {runCatching {engine.dpm.getCameraDisabled(engine.admin)}.getOrDefault(false)}
                                SettingsToggle("Block work screenshots","Apply to all apps in the work profile",TablerIcons.PhotoOff,screenshots,engine.canManage && !state.busy) {state.execute(scope) {engine.screenshots(it)}}
                                SettingsToggle("Block work camera","Prevent work apps from using the camera",TablerIcons.CameraOff,camera,engine.canManage && !state.busy) {state.execute(scope) {engine.camera(it)}}
                            }
                        }
                        item {SectionTitle("Work contacts")
                            SettingsGroup {
                                @Suppress("DEPRECATION") val contacts=remember(state.revision) {runCatching {engine.dpm.getCrossProfileContactsSearchDisabled(engine.admin)}.getOrDefault(false)}
                                @Suppress("DEPRECATION") val caller=remember(state.revision) {runCatching {engine.dpm.getCrossProfileCallerIdDisabled(engine.admin)}.getOrDefault(false)}
                                SettingsToggle("Block contact search","Hide work contacts from searches in personal apps",TablerIcons.Search,contacts,engine.canManage && !state.busy) {state.execute(scope) {engine.contactsSearch(it)}}
                                SettingsToggle("Block work caller ID","Hide work caller information in personal apps",TablerIcons.DeviceMobile,caller,engine.canManage && !state.busy) {state.execute(scope) {engine.callerId(it)}}
                            }
                        }
                        item {Hint("Policy states are read from Android. Other device policies can prevent changes to some settings.")}
                    }
                    Page.Appearance -> {
                        item {SectionTitle("Display")
                            SettingsGroup {
                                SettingsRow("Theme",state.theme.replaceFirstChar {it.uppercase()},TablerIcons.Moon) {state.dialog=AppDialog.Choice("Theme",listOf("System default" to "Follow the device appearance","Light" to "","Dark" to ""),listOf("system","light","dark").indexOf(state.theme).coerceAtLeast(0)) {index ->state.theme=listOf("system","light","dark")[index];state.prefs.set("theme",state.theme)}}
                                SettingsRow("Accent color",state.accent.replaceFirstChar {it.uppercase()},TablerIcons.Palette) {state.dialog=AppDialog.Choice("Accent color",listOf("Blue" to "","Violet" to "","Green" to ""),listOf("blue","violet","green").indexOf(state.accent).coerceAtLeast(0)) {index ->state.accent=listOf("blue","violet","green")[index];state.prefs.set("accent_v2",state.accent)}}
                                SettingsToggle("Blur navigation bar","Subtle background blur behind the bottom navigation",TablerIcons.Adjustments,state.navigationBlur) {state.navigationBlur=it;state.prefs.set("navigationBlur",it)}
                            }
                        }
                        item {SectionTitle("Capture protection")
                            SettingsGroup {SettingsToggle("Protect Penumbra screenshots","Also hide the app in the recent apps preview",TablerIcons.PhotoOff,state.captureGuard) {state.captureGuard=it;state.prefs.set("captureGuard",it)}}
                        }
                        item {Hint("Appearance preferences apply separately to the personal and work copies of Penumbra.")}
                    }
                    Page.Diagnostics -> {
                        item {SectionTitle("Device & profile")
                            SettingsGroup {
                                BasicComponent(title="Android version",summary="${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",startAction={AppIcon(TablerIcons.DeviceMobile)})
                                BasicComponent(title="Profile owner",summary=if(engine.isOwner) "Yes" else "No",startAction={AppIcon(TablerIcons.Shield)})
                                BasicComponent(title="Managed profile",summary=if(engine.isManaged) "Yes" else "No",startAction={AppIcon(TablerIcons.Briefcase)})
                            }
                        }
                        item {SectionTitle("Logs")
                            SettingsGroup {
                                SettingsRow("Export diagnostics","Includes package names from the local action log",TablerIcons.Download,!state.busy) {state.confirm("Export diagnostics?","The file includes device information and the package names of apps you managed. Review it before sharing.","Export") {export.launch("Penumbra-diagnostics.txt")}}
                                SettingsRow("Clear local log",null,TablerIcons.Trash,!state.busy) {state.confirm("Clear local log?","This deletes the local action history. It does not change your apps or policies.","Clear") {state.prefs.set("log","");state.notice="Local log cleared"}}
                                SettingsRow("Repair profile bridge","Restore the link from the personal copy",TablerIcons.Refresh,engine.canManage && !state.busy) {state.execute(scope,"Profile bridge repaired") {engine.configureBridge()}}
                            }
                        }
                    }
                    Page.About -> {
                        item {SettingsGroup {
                            BasicComponent(title="Penumbra",summary="Version ${BuildConfig.VERSION_NAME}",startAction={AppIcon(TablerIcons.Briefcase,color=colors.primary)})
                            BasicComponent(title="Package",summary=BuildConfig.APPLICATION_ID)
                            BasicComponent(title="Android support",summary="Android 11 and later · Target API 37")
                            BasicComponent(title="Interface",summary="COUI 1.1.0 · Haze 2.1.0 · Tabler Icons")
                        }}
                        item {Hint("An independent work-profile manager. App settings and action logs stay on this device. No advertising or analytics.")}
                        item {SectionTitle("Open source")
                            SettingsGroup {
                                BasicComponent(title="COUI",summary="suqi8 and the COUI / Miuix contributors · Apache 2.0")
                                BasicComponent(title="Haze",summary="Chris Banes and contributors · Apache 2.0")
                                BasicComponent(title="Tabler Icons",summary="Tabler contributors · MIT. Compose bindings by DevSrSouza.")
                            }
                        }
                    }
                }
            }
        }
        AppSheets(state) {app ->
            state.launch {
                val request=engine.uninstallIntent(app.packageName)
                pendingUninstall=app.packageName
                try {uninstall.launch(request)} catch(e: Exception) {pendingUninstall=null;throw e}
            }
        }
        ConfirmDialog(state.dialog) {state.dialog=null}
    }
}

@Composable private fun AppSheets(state: UiState, uninstall: (AppEntry) -> Unit) {
    val scope=rememberCoroutineScope()
    val engine=state.engine
    val sheet=state.sheet
    val colors=COUITheme.colorScheme
    var permissions by remember {mutableStateOf(emptyList<Pair<String,Int>>())}
    var loading by remember {mutableStateOf(false)}
    LaunchedEffect(sheet) {
        if(sheet is AppSheet.Permissions) {
            loading=true
            permissions=emptyList()
            try {permissions=withContext(Dispatchers.IO) {engine.runtimePermissions(sheet.app.packageName)}}
            catch(e: Exception) {state.sheet=null;state.error(e)}
            finally {loading=false}
        }
    }
    fun dismiss() {state.sheet=null}
    WindowBottomSheet(show=sheet!=null,title=when(sheet) {is AppSheet.Actions -> "App actions";is AppSheet.Permissions -> "App permissions";else -> "Filter apps"},
        onDismissRequest={dismiss()},endAction={ToolbarAction(TablerIcons.X,"Close") {dismiss()}}) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(bottom=8.dp)) {
            when(sheet) {
                is AppSheet.Filters -> {
                    SettingsToggle("Show system apps","Include preinstalled apps and system components",TablerIcons.DeviceMobile,state.showSystem) {state.showSystem=it;state.prefs.set("showSystem",it)}
                    if(engine.canManage) {
                        HorizontalDivider()
                        listOf("All apps","Active apps","Frozen apps","Hidden apps").forEachIndexed {index,label ->RadioButtonPreference(label,state.filter==index,{state.filter=index})}
                    }
                }
                is AppSheet.Actions -> {
                    val app=sheet.app
                    BasicComponent(title=app.label,summary=app.packageName,startAction={PackageIcon(state.activity,app.packageName,48)})
                    HorizontalDivider()
                    if(app.protected) Hint("This component is protected to keep Android and the work profile usable.")
                    if(engine.canManage) {
                        val launchable=remember(state.revision,app.packageName) {state.activity.packageManager.getLaunchIntentForPackage(app.packageName)!=null}
                        SettingsRow("Open app",when {app.hidden -> "Unhide the app first";app.suspended -> "Unfreeze the app first";!launchable -> "This app has no launcher activity";else -> null},TablerIcons.ExternalLink,launchable && !app.hidden && !app.suspended && !state.busy) {dismiss();state.launch {state.activity.startActivity(engine.launchIntent(app.packageName))}}
                        SettingsRow(if(app.suspended) "Unfreeze app" else "Freeze app",if(app.suspended) "Allow the app to run again" else "Pause the app and its notifications",TablerIcons.Snowflake,!app.protected && !state.busy) {dismiss();state.execute(scope,if(app.suspended) "App unfrozen" else "App frozen") {engine.suspend(app.packageName,!app.suspended)}}
                        SettingsRow(if(app.hidden) "Unhide app" else "Hide app","Keep its data in the work profile",if(app.hidden) TablerIcons.Eye else TablerIcons.EyeOff,!app.protected && !state.busy) {
                            dismiss();state.confirm(if(app.hidden) "Unhide app?" else "Hide app?","${app.label} and its data will remain in the work profile.",if(app.hidden) "Unhide" else "Hide") {state.execute(scope) {engine.hide(app.packageName,!app.hidden)}}
                        }
                        SettingsRow("Permissions","Manage runtime permissions",TablerIcons.ShieldLock,!app.protected && !state.busy) {state.sheet=AppSheet.Permissions(app)}
                        val blocked=remember(state.revision,app.packageName) {runCatching {engine.dpm.isUninstallBlocked(engine.admin,app.packageName)}.getOrDefault(true)}
                        SettingsRow(if(blocked) "Allow uninstall" else "Protect from uninstall",null,TablerIcons.Lock,!app.protected && !state.busy) {dismiss();state.execute(scope) {engine.blockUninstall(app.packageName,!blocked)}}
                        SettingsRow("Uninstall",when {blocked -> "Turn off Protect from uninstall first";app.hidden -> "Unhide the app first";else -> "Android will ask for confirmation"},TablerIcons.Trash,!app.protected && !blocked && !app.hidden && !state.busy) {dismiss();uninstall(app)}
                    } else {
                        SettingsRow("Copy to work profile","Install a separate copy without app data",TablerIcons.Copy,!app.protected && !state.busy) {dismiss();state.launch {engine.sendToWork(app.packageName)}}
                    }
                    SettingsRow("App info",if(app.hidden) "Unhide the app first" else "Open Android app settings",TablerIcons.InfoCircle,!app.hidden && !state.busy) {dismiss();state.launch {state.activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.packageName}")))}}
                }
                is AppSheet.Permissions -> {
                    BasicComponent(title=sheet.app.label,summary="Android may restrict changes to some permissions",startAction={PackageIcon(state.activity,sheet.app.packageName)})
                    if(loading) Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center) {InfiniteProgressIndicator()}
                    else if(permissions.isEmpty()) Hint("This app has no manageable runtime permissions.")
                    permissions.forEach { (permission,current) ->
                        SettingsRow(permission.substringAfterLast('.').lowercase(Locale.ENGLISH).replace('_',' ').replaceFirstChar {it.uppercase()},listOf("System default","Allowed by Penumbra","Denied by Penumbra").getOrElse(current) {"Unknown"},TablerIcons.Shield,!state.busy) {
                            val app=sheet.app;dismiss()
                            state.dialog=AppDialog.Choice("Permission",listOf("System default" to permission,"Allow" to "Grant this permission through Penumbra","Deny" to "Block this permission through Penumbra"),current) {value ->state.execute(scope) {engine.permission(app.packageName,permission,value)}}
                        }
                    }
                }
                null -> Unit
            }
        }
    }
}
