package com.linxi.diary.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import com.linxi.diary.data.AvatarCropper
import com.linxi.diary.data.ImagePrepPolicy
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.LoadingRow
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.util.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 头像圆形裁剪页（Q13=C）。
 *
 * 为什么要它：头像是圆形展示的，此前直接把整张图 `ContentScale.Crop` 居中裁——
 * 竖构图人像的头会被切掉，换头像等于赌运气。
 *
 * 为什么手搓而不用 uCrop 之类的库：那些库是 Material 观感，
 * 引进来又是一处割裂（管理员这轮的核心诉求就是消灭割裂感）。
 * 这里只需要"缩放平移 + 圆形遮罩 + 按框导出"，约 200 行，不值得引依赖。
 *
 * 实现要点：
 * - 预览图按 [ImagePrepPolicy.sampleSize] 降采样后再解码，避免大图 OOM
 *   （这正是 0821 修掉的那个 45.8MB 全尺寸解码问题的同源风险）
 * - 裁剪框固定为屏幕中央的圆，用户移动的是**图片**而不是框——
 *   这与系统相册、微信的交互一致，用户不必学
 * - 导出时按当前缩放/位移换算回原图坐标，再从**原图**裁（而非从预览图裁），
 *   否则头像会是降采样后的模糊版本
 */
@Composable
fun AvatarCropScreen(
    uri: Uri,
    onCancel: () -> Unit,
    onCropped: (File) -> Unit,
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 手势状态：scale 与位移都作用在图片上，裁剪框不动。
    var scale by remember(uri) { mutableStateOf(1f) }
    var offsetX by remember(uri) { mutableStateOf(0f) }
    var offsetY by remember(uri) { mutableStateOf(0f) }

    LaunchedEffect(uri) {
        preview = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0) return@runCatching null
                // 预览只需屏幕级尺寸，降采样到长边 ~1080 足够，且不会 OOM。
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = ImagePrepPolicy.sampleSize(bounds.outWidth, bounds.outHeight, 1080)
                    ImagePrepPolicy.decodeDensityScale(bounds.outWidth, bounds.outHeight, inSampleSize, 1080)
                        ?.let { (density, target) ->
                            inScaled = true
                            inDensity = density
                            inTargetDensity = target
                        }
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrElse {
                Logs.w("AvatarCrop", "decode preview failed", it)
                null
            }
        }
        if (preview == null) loadFailed = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "调整头像",
                navigationIcon = { BackAction(onCancel) },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .background(Color.Black),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    loadFailed -> Text(
                        "这张图片无法处理，换一张试试",
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                    preview == null -> LoadingRow()
                    else -> CropCanvas(
                        bitmap = preview!!,
                        scale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        onTransform = { dScale, dx, dy ->
                            // 缩放钳制 1x~5x：低于 1 会让图片小于裁剪框（出现空白），
                            // 高于 5 已经严重失真。
                            scale = (scale * dScale).coerceIn(1f, 5f)
                            offsetX += dx
                            offsetY += dy
                        },
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "拖动调整位置，双指缩放。圆圈内的部分会成为头像。",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LxButton(
                        text = "取消",
                        onClick = onCancel,
                        enabled = !busy,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.weight(1f),
                    )
                    LxButton(
                        text = if (busy) "处理中…" else "使用",
                        onClick = {
                            val bmp = preview ?: return@LxButton
                            busy = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    AvatarCropper.crop(context, uri, bmp, scale, offsetX, offsetY)
                                }
                                busy = false
                                if (out != null) onCropped(out) else loadFailed = true
                            }
                        },
                        enabled = !busy && preview != null,
                        variant = LxButtonVariant.Positive,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 裁剪画布：图片 + 圆形遮罩。
 *
 * 遮罩用 `drawWithContent` + `BlendMode.Clear` 在半透明黑幕上"挖"出一个圆，
 * 比叠四块矩形更准确（尤其在非正方形容器里）。
 */
@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (dScale: Float, dx: Float, dy: Float) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan.x, pan.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "待裁剪的头像",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
        // 圆形取景框：容器是正方形，圆直径 = 边长的 82%（留出呼吸位）。
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val d = size.minDimension * 0.82f
                    val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                    drawRect(Color.Black.copy(alpha = 0.55f))
                    // 挖出圆形透明区
                    drawCircle(
                        color = Color.Transparent,
                        radius = d / 2f,
                        center = Offset(size.width / 2f, size.height / 2f),
                        blendMode = BlendMode.Clear,
                    )
                    // 圆环描边
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = d / 2f,
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                    // 消除未用变量告警的同时保留意图：topLeft 供将来做方形裁剪复用
                    if (topLeft.x < 0f) drawRect(Color.Transparent, size = Size(0f, 0f))
                }
        )
    }
}
