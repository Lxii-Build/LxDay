package com.linxi.diary.data

import android.content.Context
import android.net.Uri
import com.linxi.diary.util.Logs
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * 一次上传失败的可展示原因。
 *
 * 此前上传失败只有一句「常见原因：格式不支持或超过 20MB」，把这些完全不同的原因
 * 全糊在一起：客户端解码失败(OOM)、处理后仍超限、服务端魔数拒绝、HEIC 无解码器、
 * 当日配额用尽、网络中断、挂接相册失败。管理员因此只能反馈"有些图片会消失"，
 * 我也无法据此定位。现在每张失败都带上具体原因与「能否重试」。
 *
 * @param uri 原始 uri，供「重试失败项」再走一遍
 * @param retryable 重试是否有意义。格式不支持重试多少次都一样，网络错误则值得重试。
 */
data class UploadOutcome(
    val message: String,
    val retryable: Boolean,
    val uri: Uri?,
    val idempotencyKey: String,
)

/**
 * 单张照片上传：本地预处理 → POST /media → 挂进目标相册。
 *
 * 抽成独立对象而非留在 Composable 里，是为了让「重试失败项」与将来的
 * 前台 Service 批量上传能复用同一条链路，不必把逻辑抄第二遍。
 */
object PhotoUploader {

    /**
     * 上传一张。成功返回 null，失败返回可展示的原因。
     *
     * @param albumId 目标相册；0 表示保持「未归类」，无需挂接
     */
    suspend fun uploadOne(
        context: Context,
        uri: Uri,
        albumId: Long,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): UploadOutcome? {
        // ① 本地预处理：EXIF 旋正 + 解码期缩放到长边 2048 + 按目标 MIME 重编码。
        val preparedResult = ImagePrep.prepare(
            context, uri, maxUploadBytes = ClientRuntimeConfig.photoMaxBytes,
        )
        val prepared = preparedResult.getOrNull()
        if (prepared == null) {
            val cause = preparedResult.exceptionOrNull()
            rethrowCancellation(cause)
            Logs.w("Upload", "prepare failed for $uri", cause)
            return UploadOutcome(
                message = prepareFailureMessage(cause),
                // 解码失败/OOM 换个时机重试有可能成功（内存压力是瞬时的）。
                retryable = true,
                uri = uri,
                idempotencyKey = idempotencyKey,
            )
        }

        // ② 上传
        val uploadResult = runCatching {
            ApiClient.uploadMedia(prepared.file, prepared.mime, prepared.takenAtMs, idempotencyKey)
        }
        prepared.file.delete()

        val media = uploadResult.getOrNull()
        if (media == null) {
            val cause = uploadResult.exceptionOrNull()
            rethrowCancellation(cause)
            Logs.w("Upload", "upload failed for $uri", cause)
            val bizCode = (cause as? ApiException)?.bizCode ?: -1
            return UploadOutcome(
                // 服务端已按拒绝原因分了业务码与中文文案，优先原样展示。
                message = cause?.message?.takeIf { it.isNotBlank() } ?: "上传失败",
                retryable = isRetryableUploadCode(bizCode),
                uri = uri,
                idempotencyKey = idempotencyKey,
            )
        }

        // ③ 挂进目标相册。
        //
        // 此前这一步失败只写日志，照片会**静默留在「未归类」**而不是目标相册——
        // 用户以为传进相册了，结果不在，又是一条"照片消失"的来源。现在明确报出来。
        val photoId = media.optLong("id")
        // 记下「这张照片对应本机哪个 uri」：之后查看自己传的照片直接读本机原图，
        // 零流量、零等待，且画质是真原图而非压过的 2048（管理员 Q24 的自定义方案）。
        if (photoId > 0) {
            LocalPhotoIndex.remember(context, photoId, uri)
        }
        if (albumId != 0L && photoId > 0) {
            val attach = runCatching { ApiClient.attachPhotos(albumId, listOf(photoId)) }
            if (attach.isFailure) {
                val cause = attach.exceptionOrNull()
                rethrowCancellation(cause)
                Logs.w("Album", "attach photo failed", cause)
                return UploadOutcome(
                    message = "已上传，但没能放进这个相册（现在在「未归类」里）",
                    retryable = false, // 照片已在服务器上，重传会产生重复
                    uri = null,
                    idempotencyKey = idempotencyKey,
                )
            }
        }
        return null
    }

    /** 本地预处理失败的原因翻译。ImagePrep 内部用 error() 抛 IllegalStateException 带中文消息。 */
    private fun prepareFailureMessage(cause: Throwable?): String {
        val msg = cause?.message?.takeIf { it.isNotBlank() }
        return when {
            cause is OutOfMemoryError -> "这张图太大，手机内存不足，稍后重试"
            msg != null -> msg
            else -> "这张图片无法处理，换一张试试"
        }
    }

    /**
     * 哪些服务端业务码值得重试。
     *
     * 判定本体在 [UploadRetryPolicy]：`BuildConfig` 与 Android 类型在 JVM 单测里
     * 不可用，把这份映射留在这里等于永远测不到，而它与服务端常量的一致性
     * 恰恰是出过 bug 的地方（见 UploadRetryPolicy 的注释）。
     */
    private fun isRetryableUploadCode(bizCode: Int): Boolean =
        UploadRetryPolicy.isRetryable(bizCode)

    // 协程取消不是业务失败。若把 CancellationException 塞进 Result，
    // 批量上传会在用户离开页面后继续处理后续照片，造成无法停止的后台工作。
    private fun rethrowCancellation(cause: Throwable?) {
        if (cause is CancellationException) throw cause
    }
}
