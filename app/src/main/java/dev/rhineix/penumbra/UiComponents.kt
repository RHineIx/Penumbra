package dev.rhineix.penumbra

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun AppIcon(image: ImageVector, description: String? = null, color: Color = COUITheme.colorScheme.onSurfaceSecondary) {
    Icon(imageVector=image,contentDescription=description,tint=color,modifier=Modifier.size(23.dp))
}
@Composable internal fun ToolbarAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick=onClick,enabled=enabled) { AppIcon(icon,label,if(enabled) COUITheme.colorScheme.onSurface else COUITheme.colorScheme.disabledOnSurface) }
}
@Composable internal fun SectionTitle(title: String) {
    Text(title,fontSize=13.sp,color=COUITheme.colorScheme.onSurfaceSecondary,modifier=Modifier.padding(start=18.dp,top=10.dp,bottom=7.dp))
}
@Composable internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(),cornerRadius=20.dp,insideMargin=PaddingValues(vertical=4.dp),colors=CardDefaults.defaultColors(color=COUITheme.colorScheme.surface,contentColor=COUITheme.colorScheme.onSurface)) { content() }
}
@Composable internal fun SettingsRow(title: String, summary: String? = null, icon: ImageVector,
    enabled: Boolean = true, onClick: () -> Unit) {
    ArrowPreference(title=title,summary=summary,startAction={AppIcon(icon)},enabled=enabled,onClick=onClick)
}
@Composable internal fun SettingsToggle(title: String, summary: String? = null, icon: ImageVector, checked: Boolean,
    enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SwitchPreference(title=title,summary=summary,startAction={AppIcon(icon)},checked=checked,onCheckedChange=onChange,enabled=enabled)
}
@Composable internal fun Hint(text: String) {
    Text(text,fontSize=13.sp,lineHeight=19.sp,color=COUITheme.colorScheme.onSurfaceSecondary,modifier=Modifier.padding(horizontal=18.dp,vertical=8.dp))
}
@Composable internal fun PackageIcon(context: Context, pkg: String, size: Int = 44) {
    val bitmap by produceState<ImageBitmap?>(null,pkg) {
        value=withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationInfo(pkg,android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES).loadIcon(context.packageManager).toBitmap(144,144).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(size.dp),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it,contentDescription=null,modifier=Modifier.fillMaxSize()) } ?: AppIcon(TablerIcons.Package)
    }
}
@Composable internal fun ScreenEmpty(icon: ImageVector, title: String, summary: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(40.dp),tint=COUITheme.colorScheme.onSurfaceSecondary)
        Text(title,fontSize=17.sp)
        Text(summary,fontSize=14.sp,color=COUITheme.colorScheme.onSurfaceSecondary)
    }
}
@Composable internal fun ConfirmDialog(dialog: AppDialog?, onDismiss: () -> Unit) {
    var draft by remember(dialog) { mutableStateOf(when(dialog) {is AppDialog.Input -> dialog.initial;else -> ""}) }
    var choice by remember(dialog) { mutableIntStateOf((dialog as? AppDialog.Choice)?.selected ?: 0) }
    val colors=COUITheme.colorScheme
    val cancel={
        if(dialog is AppDialog.Choice) dialog.preview?.invoke(dialog.selected)
        onDismiss()
    }
    val title=when(dialog) {
        is AppDialog.Confirm -> dialog.title
        is AppDialog.Choice -> dialog.title
        is AppDialog.Input -> dialog.title
        is AppDialog.Error -> "Couldn’t complete action"
        null -> ""
    }
    WindowDialog(show=dialog!=null,title=title,summary=when(dialog) {
        is AppDialog.Confirm -> dialog.message
        is AppDialog.Error -> dialog.message
        else -> null
    },largeScreen=true,onDismissRequest=cancel) {
        Column(Modifier.fillMaxWidth()) {
            when(dialog) {
                is AppDialog.Input -> TextField(value=draft,onValueChange={draft=it.take(40)},label=dialog.label,singleLine=true,
                    textStyle=TextFieldDefaults.textStyle(TextFieldMode.Line).copy(color=colors.onSurface),
                    colors=TextFieldDefaults.textFieldColors(labelColor=colors.onSurfaceSecondary),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp))
                is AppDialog.Confirm -> if(dialog.requireEraseText) TextField(value=draft,onValueChange={draft=it},label="Type REMOVE to confirm",singleLine=true,
                    textStyle=TextFieldDefaults.textStyle(TextFieldMode.Line).copy(color=colors.onSurface),
                    colors=TextFieldDefaults.textFieldColors(labelColor=colors.onSurfaceSecondary),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp))
                is AppDialog.Choice -> Column(Modifier.heightIn(max=330.dp).verticalScroll(rememberScrollState())) {
                    dialog.options.forEachIndexed { index,(label,summary) ->
                        RadioButtonPreference(title=label,summary=summary.ifBlank { null },selected=choice==index,onClick={choice=index;dialog.preview?.invoke(index)})
                    }
                }
                else -> Unit
            }
            val positive=when(dialog) {
                is AppDialog.Confirm -> dialog.action
                is AppDialog.Choice -> "Done"
                is AppDialog.Input -> "Save"
                else -> "OK"
            }
            val enabled=when(dialog) {
                is AppDialog.Confirm -> !dialog.requireEraseText || draft=="REMOVE"
                is AppDialog.Input -> draft.isNotBlank()
                else -> true
            }
            val apply={
                onDismiss()
                when(dialog) {
                    is AppDialog.Confirm -> dialog.confirm()
                    is AppDialog.Input -> dialog.save(draft.trim())
                    is AppDialog.Choice -> dialog.apply(choice)
                    else -> Unit
                }
            }
            if(dialog is AppDialog.Confirm && dialog.danger) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.End) {
                    TextButton("Cancel",onDismiss,colors=ButtonDefaults.textButtonColorsBorderless())
                    TextButton(positive,apply,enabled=enabled,colors=ButtonDefaults.textButtonColorsBorderless(textColor=COUITheme.colorScheme.error))
                }
            } else DialogButtonBar(
                negative=if(dialog is AppDialog.Error) null else DialogButtonBarAction("Cancel",onClick=cancel),
                positive=DialogButtonBarAction(positive,enabled,onClick=apply),
            )
        }
    }
}
