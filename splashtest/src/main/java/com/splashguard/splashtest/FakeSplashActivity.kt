package com.splashguard.splashtest

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
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
 *   late      跳过按钮延迟 2500ms 才出现  → 应被点击（验证 6 秒窗口）
 *   late5000  跳过按钮延迟 5000ms 才出现  → 应被点击（验证 v0.1.8 的 8 秒窗口）
 *   deep      跳过按钮嵌在 6 层容器里     → 应被点击（验证节点上限/祖先深度）
 *   bigtree   250+ 节点 + 跳过按钮        → 应被点击（验证文本查找优先）
 *   closenum  「关闭广告 5s」小按钮       → 应被点击（v0.1.8 文案变体）
 *   nonclick  「跳过」文字不可点、父容器满屏 → **不应点**（宁可不点，不能点广告本体）
 *   shake     「摇一摇跳转详情」+「跳过」 → 只点跳过，不碰摇一摇
 *   shakecd   「摇一摇」写在 contentDescription → 只点跳过，不碰摇一摇
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
            "late" -> {
                // 2.5 秒才出现：v0.1.5 的 1.5 秒窗口会漏掉，v0.1.6 的 6 秒窗口应点中
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                root.postDelayed({ placeTopRight(root, btn, dp(60), dp(28)) }, 2500)
            }
            "late5000" -> {
                // 5 秒才出现：验证 v0.1.8 把窗口延长到 8 秒后仍能点中
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                root.postDelayed({ placeTopRight(root, btn, dp(60), dp(28)) }, 5000)
            }
            "closenum" -> {
                // 「关闭广告 5s」：v0.1.8 新增的安全变体
                btn.text = "关闭广告 5s"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                placeTopRight(root, btn, dp(96), dp(28))
            }
            "bigtree" -> {
                // 250+ 个无关节点：v0.1.7 的 150 节点 BFS 会截断，v0.1.8 文本查找应直接命中
                repeat(260) { i ->
                    val dummy = TextView(this).apply {
                        text = "内容$i"
                        setTextColor(Color.parseColor("#20262E"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    }
                    root.addView(dummy, FrameLayout.LayoutParams(dp(100), dp(20)).apply {
                        leftMargin = dp((i % 10) * 10)
                        topMargin = dp(80 + (i / 10) * 6)
                    })
                }
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                placeTopRight(root, btn, dp(60), dp(28))
            }
            "deep" -> {
                // 跳过按钮在 6 层容器里：验证 BFS 节点上限与祖先查找深度
                var parent: FrameLayout = root
                repeat(5) {
                    val child = FrameLayout(this)
                    parent.addView(child, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    parent = child
                }
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                placeTopRight(parent, btn, dp(60), dp(28))
            }
            "nonclick" -> {
                // 「跳过」文字节点不可点，父容器是满屏可点击广告。
                // 无障碍应改为在文字中心点一下；点整块广告会记 CLICKED:ad-area（失败）。
                btn.text = "跳过"
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                btn.isClickable = false
                placeTopRight(root, btn, dp(60), dp(28))
                root.isClickable = true
                root.setOnTouchListener { _, e ->
                    if (e.action == MotionEvent.ACTION_UP) {
                        val loc = IntArray(2)
                        btn.getLocationOnScreen(loc)
                        val inButton = e.rawX >= loc[0] && e.rawX <= loc[0] + btn.width &&
                            e.rawY >= loc[1] && e.rawY <= loc[1] + btn.height
                        Log.i(TAG, if (inButton) "CLICKED:skip" else "CLICKED:ad-area")
                    }
                    true
                }
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
            "shakecd" -> {
                // 「摇一摇」只写在 contentDescription 里，text 为空：验证不会因为读不到 text 而误点
                val shake = TextView(this).apply {
                    contentDescription = "摇一摇跳转详情"
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
        // setOnClickListener 会把 View 自动设为 clickable；nonclick 场景要在最后再关掉，
        // 才能真正验证「文字节点不可点 → 坐标点击兜底」
        if (variant == "nonclick") btn.isClickable = false
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
