package com.linxi.diary.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.exifinterface.media.ExifInterface
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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

    /**
     * 裁剪画布的实际边长（像素），由 [CropCanvas] 回传。
     *
     * 导出时必须传给 `AvatarCropper.crop` —— 位移量的坐标系是这个容器，
     * 用预览图尺寸代替会让拖动位移被按比例放大，裁出来的位置与看到的不一致。
     * 0 表示还没测量完（此时"使用"按钮也还不该点，preview 尚未就绪）。
     */
    var canvasSizePx by remember(uri) { mutableStateOf(0f) }

    LaunchedEffect(uri) {
        preview = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0) return@runCatching null
                val orientation = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ExifInterface(input).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                }.getOrElse {
                    Logs.w("AvatarCrop", "EXIF unreadable, using normal orientation", it)
                    ExifInterface.ORIENTATION_NORMAL
                }
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
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@runCatching null
                orientPreview(decoded, orientation)
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
                        onContainerSize = { canvasSizePx = it },
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
                            val side = canvasSizePx
                            if (side <= 0f) return@LxButton // 画布还没测量完
                            busy = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    // **显式传 viewSizePx**：位移量的坐标系是画布容器，
                                    // 不传就会退回默认值（预览图长边 1080），
                                    // 与容器实际边长（屏宽 ≈1272）差约 18%，拖动后裁歪。
                                    AvatarCropper.crop(
                                        context, uri, bmp, scale, offsetX, offsetY,
                                        viewSizePx = side,
                                    )
                                }
                                busy = false
                                if (out != null) onCropped(out) else loadFailed = true
                            }
                        },
                        enabled = !busy && preview != null && canvasSizePx > 0f,
                        variant = LxButtonVariant.Positive,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 预览先旋正，保证用户看到的取景框与导出时的 EXIF 坐标一致。 */
private fun orientPreview(src: Bitmap, exifOrientation: Int): Bitmap {
    val t = ImagePrepPolicy.orientationTransform(exifOrientation)
    if (t.isIdentity) return src
    val matrix = android.graphics.Matrix().apply {
        if (t.rotationDegrees != 0f) postRotate(t.rotationDegrees)
        if (t.flipHorizontal) postScale(-1f, 1f)
    }
    val oriented = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    if (oriented !== src) src.recycle()
    return oriented
}

/**
 * 裁剪画布：图片 + 圆形遮罩。
 *
 * 遮罩用 `BlendMode.Clear` 在半透明黑幕上"挖"出一个圆，
 * 比叠四块矩形更准确（尤其在非正方形容器里）。
 *
 * ## 「裁剪框中间是黑的」的原因与修法（0822）
 *
 * `BlendMode.Clear` 会把目标像素的 **alpha 一并清成 0**。遮罩层若没有自己的
 * 离屏合成层，绘制就直接落在父级画布上 —— 而图片已经画在那里了，
 * 于是 `drawCircle(Clear)` 把**图片连同黑幕一起清掉**，露出底下不透明的
 * 窗口表面，圆圈里就是一片黑。
 *
 * 修法是给遮罩层加 `CompositingStrategy.Offscreen`：让它先画到独立的离屏缓冲，
 * `Clear` 只作用于该缓冲内的内容（里面只有那层半透明黑幕），
 * 清出真正的透明区后再整层合成回去，才能透出下面的图片。
 *
 * 这类"用了 Clear/DstOut 等擦除型 BlendMode 却忘了离屏层"的问题，
 * 现象就是**该透明的地方变成黑色**。
 */
@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (dScale: Float, dx: Float, dy: Float) -> Unit,
    /**
     * 回传容器实际边长（像素）。
     *
     * **导出时必须用这个值，不能用预览图的尺寸**：`offsetX`/`offsetY` 来自
     * `detectTransformGestures` 的 pan，单位是**容器像素**；而 `AvatarCropper.crop`
     * 的 `viewSizePx` 默认值取的是预览图长边（限死 1080）。一加 15 上容器是屏宽
     * ≈1272px，两者差约 18% —— `frameToBitmapRect` 里 `fit = viewSize/bmp` 与
     * offset 混算，于是**拖动位移被放大约 18%，拖得越多偏得越远**。
     * 不拖动时正好抵消，所以"居中裁"看起来完全正常，一拖就歪。
     */
    onContainerSize: (Float) -> Unit,
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
        // 容器是 aspectRatio(1f) 的正方形，宽即边长。
        val sidePx = constraints.maxWidth.toFloat()
        LaunchedEffect(sidePx) { onContainerSize(sidePx) }
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
        // 圆形取景框：容器是正方形，圆直径 = 边长 × FRAME_RATIO。
        //
        // **必须复用 AvatarCropper.FRAME_RATIO，不能在这里另写一个数**：
        // 这里画的圈决定用户"看到"要裁哪块，而 AvatarCropper.frameToBitmapRect 用同一个
        // 比例算"实际"裁哪块。两处各写一份，一旦有人只改一边，导出的头像就与预览错位，
        // 而这种错位在小比例差下很难一眼看出来。
        Box(
            Modifier
                .fillMaxSize()
                // **必须有离屏层**，否则 BlendMode.Clear 会连图片一起擦掉、露出黑色底。
                // 详见本函数文档注释。
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val d = size.minDimension * AvatarCropper.FRAME_RATIO
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawRect(Color.Black.copy(alpha = 0.55f))
                    // 挖出圆形透明区（只擦掉上面那层黑幕，图片在离屏层之外不受影响）
                    drawCircle(
                        color = Color.Black,
                        radius = d / 2f,
                        center = center,
                        blendMode = BlendMode.Clear,
                    )
                    // 圆环描边
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = d / 2f,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
        )
    }
}
