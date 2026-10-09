package io.github.miuzarte.littlewhale.sample.companion

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 伴侣自带的那一页
 *
 * 它在演示协议 10.3 那一条: 伴侣想怎么做界面都行, LW 只给一个入口 (插件页上的「打开」按钮直接
 * `startActivity` 起这一页)。所以这一页故意什么都不做, 只有一句说明
 */
class HelloScreen : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = getString(R.string.hello_body)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(LinearLayout(this).apply { addView(view) })
    }
}
