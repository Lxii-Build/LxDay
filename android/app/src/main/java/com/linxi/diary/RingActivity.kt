package com.linxi.diary

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import com.linxi.diary.core.RingController
import com.linxi.diary.sync.StatusSyncManager

/**
 * 强制响铃全屏页：全屏 Intent 拉起后展示紧急信息，点击「我知道了」停止响铃。
 * manifest 已配置 showWhenLocked / turnScreenOn，锁屏也会亮屏显示。
 * 用 ComponentActivity（非 AppCompat），配合项目 XML 主题，避免 AppCompat 主题继承要求。
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ring)

        findViewById<Button>(R.id.btn_dismiss).setOnClickListener {
            // 记下 ringId 再停：stop 会清空会话，之后就取不到了。
            val ringId = RingController.currentRingId
            // stop 需要 Context 才能还原音量/勿扰、取消振动与通知（见 RingController）。
            RingController.stop(applicationContext, "activity-dismiss")
            // 回执给发送方，结束其"响铃中"倒计时——否则对方以为没送达会反复响铃。
            StatusSyncManager.sendRingStopped(ringId)
            finish()
        }
    }

    override fun onDestroy() {
        RingController.stop(applicationContext, "activity-destroy")
        super.onDestroy()
    }
}
