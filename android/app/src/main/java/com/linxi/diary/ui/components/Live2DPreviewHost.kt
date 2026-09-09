package com.linxi.diary.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.linxi.diary.data.Live2DModelRecord
import com.linxi.diary.data.Live2DNativeRuntime
import com.linxi.diary.data.Live2DNativeRuntimeInfo
import com.linxi.diary.data.Live2DRendererBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app can have two Compose destinations alive during AnimatedContent's
 * transition.  A Cubism renderer owns a GL context and global Framework state,
 * so allowing both destinations to create a view at once is not safe.  This
 * small lease makes the invariant structural: one renderer view per process.
 */
private object Live2DPreviewLease {
    private val lock = Any()
    private var owner: Any? = null
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    fun tryAcquire(token: Any): Boolean = synchronized(lock) {
        if (owner == null || owner === token) {
            owner = token
            true
        } else {
            false
        }
    }

    fun release(token: Any) = synchronized(lock) {
        if (owner === token) {
            owner = null
            _changes.value += 1
        }
    }
}

/**
 * Embeds the real optional Cubism GL view when the owner has supplied the
 * licensed Core AAR + Java Framework.  Without those artifacts the fallback is
 * rendered explicitly; callers must not use a static image as a fake model.
 */
@Composable
fun Live2DPreviewHost(
    model: Live2DModelRecord?,
    modifier: Modifier = Modifier,
    runtimeInfo: Live2DNativeRuntimeInfo = remember { Live2DNativeRuntime.probe() },
    fallback: @Composable (Live2DNativeRuntimeInfo) -> Unit,
) {
    val context = LocalContext.current
    val leaseToken = remember { Any() }
    // Re-attempt after the previous destination releases its GL view.  This is
    // important when the home card and manager page overlap for a transition.
    val leaseChanges by Live2DPreviewLease.changes.collectAsState()
    val nativeView = remember(model?.id, runtimeInfo.status, leaseChanges) {
        if (model == null || !runtimeInfo.canCreateRenderer || !Live2DRendererBridge.isInstalled()) {
            null
        } else if (!Live2DPreviewLease.tryAcquire(leaseToken)) {
            null
        } else {
            Live2DRendererBridge.create(context, model.directory, model.manifestPath)
                ?: run {
                    Live2DPreviewLease.release(leaseToken)
                    null
                }
        }
    }

    DisposableEffect(nativeView) {
        onDispose {
            nativeView?.let(Live2DRendererBridge::close)
            Live2DPreviewLease.release(leaseToken)
        }
    }

    Box(modifier) {
        if (nativeView != null) {
            AndroidView(
                factory = { nativeView },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                fallback(runtimeInfo)
            }
        }
    }
}
