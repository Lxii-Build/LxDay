package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.Live2DImportResult
import com.linxi.diary.data.Live2DImportPolicy
import com.linxi.diary.data.Live2DModelRecord
import com.linxi.diary.data.Live2DModelStore
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.Live2DRemoteModel
import com.linxi.diary.data.Live2DNativeRuntime
import com.linxi.diary.data.Live2DNativeRuntimeInfo
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.LxConfirmDialog
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import com.linxi.diary.ui.components.Live2DPreviewHost
import com.linxi.diary.ui.components.LxIcon as Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.MindMap
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch

/** Device-local model management with an optional, license-gated Cubism GL host. */
@Composable
fun Live2DManagerScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<Live2DModelRecord>>(emptyList()) }
    var activeModelId by remember { mutableStateOf<String?>(null) }
    var initialLoading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var compatibilityReport by remember { mutableStateOf<Live2DImportPolicy.Report?>(null) }
    var pendingDelete by remember { mutableStateOf<Live2DModelRecord?>(null) }
    var catalog by remember { mutableStateOf<List<Live2DRemoteModel>>(emptyList()) }
    var catalogBusy by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var runtimeInfo by remember { mutableStateOf(Live2DNativeRuntime.probe()) }

    fun syncModels(next: List<Live2DModelRecord>) {
        models = next
        val stored = Live2DModelStore.activeId(context)
        val selected = next.firstOrNull { it.id == stored }?.id ?: next.firstOrNull()?.id
        activeModelId = selected
        if (selected != null && selected != stored) Live2DModelStore.setActive(context, selected)
        if (selected == null && stored != null) Live2DModelStore.setActive(context, null)
    }

    fun refreshCatalog() {
        catalogBusy = true
        catalogError = null
        scope.launch {
            runCatching { ApiClient.live2dModels() }
                .onSuccess { catalog = it }
                .onFailure { catalogError = it.message ?: "资源库加载失败，请重试" }
            catalogBusy = false
        }
    }

    fun downloadModel(model: Live2DRemoteModel) {
        if (busy) return
        busy = true
        message = "正在下载并校验「${model.name}」…"
        messageError = false
        compatibilityReport = null
        scope.launch {
            val temp = java.io.File(context.cacheDir, "live2d-${model.id}.zip")
            runCatching {
                ApiClient.downloadLive2DModel(model.id, temp)
                Live2DModelStore.importFile(context, temp, "${model.name}.zip")
            }.onSuccess { result ->
                when (result) {
                    is Live2DImportResult.Success -> {
                        message = "已下载并导入「${result.model.name}」，原生渲染接入后可在此预览"
                        compatibilityReport = result.report
                        syncModels(Live2DModelStore.list(context))
                    }
                    is Live2DImportResult.Failure -> {
                        message = result.message
                        messageError = true
                        compatibilityReport = result.report
                    }
                }
            }.onFailure {
                message = it.message ?: "资源下载失败，请稍后重试"
                messageError = true
            }
            temp.delete()
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            message = "已取消导入"
            messageError = false
            return@rememberLauncherForActivityResult
        }
        busy = true
        message = "正在校验并导入模型…"
        messageError = false
        compatibilityReport = null
        scope.launch {
            val result = Live2DModelStore.importZip(context, uri, "${uri.lastPathSegment ?: "Live2D 模型"}")
            when (result) {
                is Live2DImportResult.Success -> {
                    message = "已导入「${result.model.name}」"
                    messageError = false
                    compatibilityReport = result.report
                    syncModels(Live2DModelStore.list(context))
                }
                is Live2DImportResult.Failure -> {
                    message = result.message
                    messageError = true
                    compatibilityReport = result.report
                }
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        syncModels(Live2DModelStore.list(context))
        runtimeInfo = Live2DNativeRuntime.probe()
        initialLoading = false
        refreshCatalog()
    }

    val activeModel = models.firstOrNull { it.id == activeModelId }
    KernelScreen(title = "Live2D 伴侣", navigationIcon = { BackAction(onBack) }, loading = initialLoading) {
        item {
            LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Raised) {
                Column(Modifier.padding(18.dp)) {
                    Text("本机模型库", fontSize = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "从系统文件选择器导入完整的 Cubism model3.json / moc3 / PNG 模型包。模型只保存在本机，不会自动上传。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(14.dp))
                    LxButton(
                        // Some OEM document providers report ZIPs as octet-stream;
                        // the importer still validates the archive and manifest, so
                        // widening the picker MIME list improves compatibility without
                        // weakening the safety boundary.
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        content = {
                            Icon(MiuixIcons.Import, contentDescription = "导入 ZIP")
                            Spacer(Modifier.size(8.dp))
                            Text("选择 ZIP 导入")
                        },
                    )
                    message?.let {
                        Text(
                            it,
                            color = if (messageError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    compatibilityReport?.let { report ->
                        CompatibilityReportSummary(report)
                    }
                }
            }
        }

        item {
            LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Raised) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("资源库", fontSize = 16.sp)
                            Text("仅显示管理员已发布的模型，不会自动替换当前角色", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        LxButton(
                            onClick = ::refreshCatalog,
                            enabled = !catalogBusy && !busy,
                            variant = LxButtonVariant.Neutral,
                            modifier = Modifier.size(48.dp),
                            horizontalPadding = 0,
                            content = { Icon(MiuixIcons.Recent, contentDescription = "刷新资源库") },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        catalogBusy -> Text("正在刷新资源库…", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        catalogError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                catalogError.orEmpty(),
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.error,
                            )
                            LxButton(text = "重试", onClick = ::refreshCatalog, variant = LxButtonVariant.Neutral, horizontalPadding = 14)
                        }
                        catalog.isEmpty() -> Text("资源库暂无已发布角色", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        else -> catalog.forEach { model ->
                            RemoteModelRow(model, enabled = !busy, onDownload = { downloadModel(model) })
                        }
                    }
                }
            }
        }

        item {
            LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Inset) {
                Column(Modifier.padding(18.dp)) {
                    Text("预览区域", fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Live2DPreviewHost(
                        model = activeModel,
                        runtimeInfo = runtimeInfo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(horizontal = 12.dp),
                    ) { runtime ->
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    MiuixIcons.MindMap,
                                    contentDescription = "Live2D 运行状态",
                                    tint = if (runtime.canCreateRenderer) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    when {
                                        models.isEmpty() -> "先导入完整的 model3.json / moc3 模型包"
                                        !runtime.canCreateRenderer -> "${runtime.detail}；当前仅完成安全导入，未伪造模型画面"
                                        else -> "模型宿主创建失败，请重试或重新导入角色"
                                    },
                                    fontSize = 13.sp,
                                )
                                Text(
                                    "状态：${runtimeStatusLabel(runtime.status)}",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (models.isEmpty()) {
            item {
                Text(
                    "还没有本机模型。建议使用包含 model3.json、moc3 和纹理 PNG 的官方模型包。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        } else {
            models.forEach { model ->
                item(key = model.id) {
                    ModelRow(
                        model = model,
                        active = model.id == activeModelId,
                        busy = busy,
                        onActivate = {
                            if (!busy && Live2DModelStore.setActive(context, model.id)) {
                                activeModelId = model.id
                                message = "已切换到「${model.name}」"
                                messageError = false
                            }
                        },
                        onDelete = {
                            pendingDelete = model
                        },
                    )
                }
            }
        }
    }

    val deleting = pendingDelete
    LxConfirmDialog(
        show = deleting != null,
        title = "删除本机模型？",
        message = deleting?.let { "删除后仅移除 APP 内的「${it.name}」副本，不会删除你在文件选择器中的原始 ZIP。" }.orEmpty(),
        confirmText = "删除副本",
        destructive = true,
        busy = busy,
        busyText = "删除中…",
        onDismiss = { if (!busy) pendingDelete = null },
        onConfirm = {
            pendingDelete?.let { target ->
                busy = true
                scope.launch {
                    val deleted = Live2DModelStore.delete(context, target.id)
                    message = if (deleted) "已删除「${target.name}」" else "删除失败，请稍后重试"
                    messageError = !deleted
                    syncModels(Live2DModelStore.list(context))
                    busy = false
                    pendingDelete = null
                }
            }
        },
    )
}

@Composable
private fun CompatibilityReportSummary(report: Live2DImportPolicy.Report) {
    val required = if (report.canRender) "首帧必需资源齐全" else "仍不能渲染首帧"
    val optional = report.optionalResourceCount
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Text(
            "兼容性检查：${required} · ${report.textureCount} 张贴图 · ${optional} 个可选资源",
            fontSize = 12.sp,
            color = if (report.canRender) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.error
            },
        )
        if (report.hasVtubeStudioConfig) {
            Text(
                "已识别 VTube Studio 配置；它只用于动作/表情/位置元数据，不替代 moc3。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        report.textureWarnings.firstOrNull()?.let { warning ->
            Text(
                warning,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        report.missingOptional.take(2).forEach { missing ->
            Text(
                "可选资源未找到：${missing}",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: Live2DModelRecord,
    active: Boolean,
    busy: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    LxSurface(Modifier.fillMaxWidth().padding(top = 10.dp), tone = LxSurfaceTone.Raised) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .then(Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MiuixIcons.MindMap, contentDescription = "模型", tint = MiuixTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(model.name, fontSize = 15.sp)
                Text(
                    "${formatBytes(model.bytes)} · ${if (active) "当前使用" else "已导入"}",
                    fontSize = 12.sp,
                    color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                model.compatibilityWarnings.firstOrNull()?.let { warning ->
                    Text(
                        warning,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            LxButton(
                text = if (active) "当前" else "使用",
                onClick = onActivate,
                enabled = !busy && !active,
                variant = if (active) LxButtonVariant.Neutral else LxButtonVariant.Positive,
                horizontalPadding = 12,
            )
            LxButton(
                onClick = onDelete,
                enabled = !busy,
                variant = LxButtonVariant.Negative,
                modifier = Modifier.size(48.dp),
                horizontalPadding = 0,
                content = { Icon(MiuixIcons.Delete, contentDescription = "删除模型") },
            )
        }
    }
}

@Composable
private fun RemoteModelRow(model: Live2DRemoteModel, enabled: Boolean, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.name, fontSize = 14.sp)
            Text(
                "v${model.version} · ${formatBytes(model.bytes)} · ${model.textureCount} 张纹理",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        LxButton(
            text = "下载",
            onClick = onDownload,
            enabled = enabled,
            horizontalPadding = 14,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun runtimeStatusLabel(status: Live2DNativeRuntimeInfo.Status): String = when (status) {
    Live2DNativeRuntimeInfo.Status.MissingCore -> "等待 Core AAR"
    Live2DNativeRuntimeInfo.Status.MissingFramework -> "等待 Java Framework"
    Live2DNativeRuntimeInfo.Status.CoreUnavailable -> "Core native 库不可用"
    Live2DNativeRuntimeInfo.Status.Ready -> "Cubism Core 可用"
}
