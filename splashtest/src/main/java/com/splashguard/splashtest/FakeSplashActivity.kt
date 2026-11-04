package com.splashguard.splashtest

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 假开屏页。用 intent extra 选择考验点：
 *   skip      右上角小按钮「跳过」        → 应被点击
 *   skipcount 右上角小按钮「跳过 3」      → 应被点击
 *   huge      全宽大按钮「跳过广告」      → 不应点击（尺寸守卫生效）
 *   other     小按钮「关闭」              → 不应点击（文案不在白名单）
 *   ad        可点击的整块广告 + 无跳过   → 不应点击
 *   delay     跳过按钮延迟 600ms 才出现   → 应被点击（验证抢跑轮询）
 *   shake     「摇一摇跳转详情」+「跳过」 → 只点跳过，不碰摇一摇
 *
 * 点击后写 logcat：tag=FakeSplash, msg=CLICKED:<variant>
 */
class FakeSplashActivity : Activity() {

    private var variant: String = "skip"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        variant = intent?.getStringExtra("variant") ?: "skip"
        Log.i(TAG, "opened variant=$variant")

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101418")) }
        val ad = TextView(this).apply {
            text = "广告位（整块可点击）"
            setTextColor(Color.parseColor("#5A6472"))
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { Log.i(TAG, "CLICKED:ad-area") }
        }
        root.addView(ad, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val btn = TextView(this).apply {
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A4149"))
        }

        when (variant) {
            "skip" -> {
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                placeTopRight(root, btn, dp(60), dp(28))
            }
            "skipcount" -> {
                btn.text = "跳过 3"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                placeTopRight(root, btn, dp(72), dp(28))
            }
            "huge" -> {
                btn.text = "跳过广告"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                root.addView(btn, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(200)).apply {
                    gravity = Gravity.BOTTOM
                })
            }
            "other" -> {
                btn.text = "关闭"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                placeTopRight(root, btn, dp(60), dp(28))
            }
            "delay" -> {
                // 跳过按钮延迟 600ms 才渲染：模拟真实广告 SDK 的异步填充
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                root.postDelayed({ placeTopRight(root, btn, dp(60), dp(28)) }, 600)
            }
            "shake" -> {
                // 左上角「摇一摇跳转详情」—— 越像广告越好，验证不会被误点
                val shake = TextView(this).apply {
                    text = "摇一摇跳转详情"
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    isClickable = true
                    setBackgroundColor(Color.parseColor("#7A2E2E"))
                    setOnClickListener { Log.i(TAG, "CLICKED:shake-ad") }
                }
                placeTopLeft(root, shake, dp(150), dp(30))
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                placeTopRight(root, btn, dp(60), dp(28))
            }
            else -> { /* ad: 只有广告区域 */ }
        }

        btn.setOnClickListener {
            Log.i(TAG, "CLICKED:$variant")
            finish()
        }
        setContentView(root)
    }

    private fun placeTopRight(root: FrameLayout, v: TextView, w: Int, h: Int) {
        root.addView(v, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(48)
            marginEnd = dp(16)
        })
    }

    private fun placeTopLeft(root: FrameLayout, v: TextView, w: Int, h: Int) {
        root.addView(v, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(48)
            marginStart = dp(16)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FakeSplash"
    }
}
