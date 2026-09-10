package com.linxi.diary.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.linxi.diary.data.NeteaseAccountStore
import com.linxi.diary.data.NeteaseQrLoginClient
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 网易云扫码登录：二维码只在设备上显示，轮询结果也不经过林曦服务端。 */
class NeteaseQrLoginActivity : ComponentActivity() {
    private var statusText by mutableStateOf("正在生成二维码…")
    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KernelScreen(title = "网易云扫码绑定") {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "网易云登录二维码",
                                modifier = Modifier.size(280.dp),
                            )
                        }
                        Text(statusText, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        LxButton(
                            text = "取消",
                            onClick = { finish() },
                            variant = LxButtonVariant.Neutral,
                        )
                    }
                }
            }
        }
        startLogin()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun startLogin() {
        lifecycleScope.launch {
            val client = NeteaseQrLoginClient()
            runCatching { client.createSession() }
                .onFailure { statusText = "二维码生成失败，请稍后重试" }
                .onSuccess { session ->
                    qrBitmap = createQrBitmap(session.qrContent, 720)
                    statusText = "请用网易云 App 扫码登录"
                    while (!cancelled) {
                        delay(2_000L)
                        val result = runCatching { client.poll(session) }.getOrNull()
                        if (result == null) {
                            statusText = "网络不稳定，正在重试…"
                            continue
                        }
                        when (result.code) {
                            801 -> statusText = "等待扫码…"
                            802 -> statusText = "已扫码，请在网易云确认登录"
                            803 -> {
                                if (NeteaseAccountStore.saveCookies(result.cookies)) {
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                } else {
                                    statusText = "登录凭据无效，请重新扫码"
                                }
                                break
                            }
                            800 -> {
                                statusText = "二维码已过期，请返回后重新打开"
                                break
                            }
                            else -> statusText = result.message.ifBlank { "扫码状态异常，请重试" }
                        }
                    }
                }
        }
    }

    private fun createQrBitmap(content: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) {
                for (x in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}
