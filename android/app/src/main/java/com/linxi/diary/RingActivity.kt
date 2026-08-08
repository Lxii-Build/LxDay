package com.linxi.diary

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import com.linxi.diary.core.RingController

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
            RingController.stop()
            finish()
        }
    }

    override fun onDestroy() {
        RingController.stop()
        super.onDestroy()
    }
}
