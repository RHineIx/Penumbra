package dev.rhineix.penumbra

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Page(val title: String) {
    Work("Penumbra"), Apps("Apps"), Settings("Settings"), Privacy("Privacy controls"),
    Appearance("Appearance"), Diagnostics("Diagnostics"), About("About")
}
internal sealed interface AppDialog {
    data class Confirm(val title: String, val message: String, val action: String, val danger: Boolean = false,
        val requireEraseText: Boolean = false, val confirm: () -> Unit) : AppDialog
    data class Choice(val title: String, val options: List<Pair<String,String>>, val selected: Int,
        val preview: ((Int) -> Unit)? = null,
        val apply: (Int) -> Unit) : AppDialog
    data class Input(val title: String, val label: String, val initial: String, val save: (String) -> Unit) : AppDialog
    data class Error(val message: String) : AppDialog
}
internal sealed interface AppSheet {
    data class Actions(val app: AppEntry) : AppSheet
    data object Filters : AppSheet
    data class Permissions(val app: AppEntry) : AppSheet
}
internal class UiState(val activity: MainActivity) {
    val engine = ProfileEngine(activity)
    val prefs = engine.prefs
    private val restored = activity.savedStateRegistry.consumeRestoredStateForKey("penumbra.ui")
    private val openWorkControls=activity.intent.getBooleanExtra("workControls",false)
    private val backStack = mutableStateListOf<Page>().apply {
        if(!openWorkControls) restored?.getStringArrayList("backStack")?.forEach { name -> Page.entries.find { it.name==name }?.let(::add) }
    }
    var page by mutableStateOf(if(openWorkControls) Page.Work else Page.entries.find {it.name==restored?.getString("page")} ?: Page.Work)
    var revision by mutableIntStateOf(0)
    var theme by mutableStateOf(prefs.text("theme", "system"))
    var accent by mutableStateOf(prefs.text("accent_v2", "blue"))
    var monet by mutableStateOf(android.os.Build.VERSION.SDK_INT>=31 && prefs.bool("monet"))
    var navigationBlur by mutableStateOf(prefs.bool("navigationBlur", true))
    var captureGuard by mutableStateOf(prefs.bool("captureGuard"))
    var showSystem by mutableStateOf(prefs.bool("showSystem"))
    var filter by mutableIntStateOf(restored?.getInt("filter") ?: 0)
    var search by mutableStateOf(restored?.getString("search") ?: "")
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(emptySet<String>())
    var apps by mutableStateOf(emptyList<AppEntry>())
    var loadingApps by mutableStateOf(false)
    var hasWork by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<AppDialog?>(null)
    var sheet by mutableStateOf<AppSheet?>(null)
    val mainPage get() = page in listOf(Page.Work, Page.Apps, Page.Settings)
    init {
        activity.intent.removeExtra("workControls")
        activity.savedStateRegistry.registerSavedStateProvider("penumbra.ui") {
            android.os.Bundle().apply {
                putString("page",page.name);putString("search",search);putInt("filter",filter)
                putStringArrayList("backStack",ArrayList(backStack.map {it.name}))
            }
        }
    }
    fun navigate(page: Page) {
        if(page in listOf(Page.Work,Page.Apps,Page.Settings)) backStack.clear()
        else if(page!=this.page) backStack.add(this.page)
        this.page=page; selecting=false; selected=emptySet()
    }
    fun back() { page=if(backStack.isEmpty()) Page.Settings else backStack.removeAt(backStack.lastIndex) }
    fun error(error: Throwable) { dialog = AppDialog.Error(error.message ?: error.javaClass.simpleName) }
    fun launch(block: () -> Unit) { try { block() } catch(e: Exception) { error(e) } }
    fun confirm(title: String, message: String, action: String, danger: Boolean = false, erase: Boolean = false, block: () -> Unit) {
        dialog = AppDialog.Confirm(title,message,action,danger,erase,block)
    }
    fun execute(scope: CoroutineScope, success: String = "Changes saved", onSuccess: () -> Unit = {}, block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                onSuccess()
                if(success.isNotBlank()) notice = success
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { prefs.log("${e.javaClass.simpleName}: ${e.message}"); error(e) }
            finally { busy = false; revision++ }
        }
    }
    fun toggleSelection(pkg: String) { selected = if(pkg in selected) selected-pkg else selected+pkg }
}
