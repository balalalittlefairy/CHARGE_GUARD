package io.github.chargercontroller

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.chargercontroller.network.XiaomiHttp

/** Own full-height content: AlertDialog's wrap-content panel can measure a weighted WebView to zero. */
class XiaomiLoginDialog(
    private val activity: Activity,
    private val initialUrl: String,
    private val smsLogin: Boolean,
    private val completed: (List<String>, (Boolean) -> Unit) -> Unit,
) : Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar) {
    internal lateinit var webView: WebView
        private set
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var confirm: Button
    private lateinit var retry: Button
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(XiaomiHttp.allowed(initialUrl))
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        fun label(value: String, size: Float) = TextView(activity).apply {
            text = value; textSize = size; setTextColor(Color.rgb(22, 38, 58))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label(if (smsLogin) "手机号验证码登录" else "小米身份验证", 20f)
        label("小米官方网页 · account.xiaomi.com", 13f)
        message = label(if (smsLogin) "输入手机号并按网页提示填写验证码，完成后点击下方“获取设备”。" else "请在网页完成验证后点击下方按钮。", 13f)
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = WebSettings.getDefaultUserAgent(activity)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (XiaomiHttp.allowed(request.url.toString())) return false
                    if (request.isForMainFrame) showProblem("已阻止离开小米登录域名；请使用手机号登录。")
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    this@XiaomiLoginDialog.progress.visibility = View.VISIBLE
                    message.text = "正在加载小米网页…"
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    this@XiaomiLoginDialog.progress.visibility = View.GONE
                    if (message.text == "正在加载小米网页…") message.text = "网页加载完成；请按页面提示操作，登录成功后点击“获取设备”。"
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showProblem("网页加载失败（${error.errorCode}），请检查手机网络后重试。")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) showProblem("小米网页返回 HTTP ${response.statusCode}，请稍后重试。")
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(label: String, click: () -> Unit) = Button(activity).apply {
            text = label; isAllCaps = false
            actions.addView(this, LinearLayout.LayoutParams(0, dp(56), 1f))
            setOnClickListener { click() }
        }
        button("取消") { if (!checking) dismiss() }
        retry = button("重新加载") { if (!checking) webView.loadUrl(initialUrl) }
        confirm = button("获取设备") {
            if (!checking) {
                checking = true; confirm.isEnabled = false; retry.isEnabled = false
                message.text = "正在获取登录会话…"
                val urls = listOf("https://account.xiaomi.com/", "https://account.xiaomi.com/pass/", initialUrl) + listOfNotNull(webView.url)
                completed(urls.distinct().filter(XiaomiHttp::allowed)) { successful ->
                    if (successful) dismiss() else {
                        checking = false; confirm.isEnabled = true; retry.isEnabled = true
                        message.text = "尚未取得登录会话，请完成网页登录后再点“获取设备”。"
                    }
                }
            }
        }
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        setContentView(root, android.view.ViewGroup.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        root.requestApplyInsets()
        // A normal Dialog with MATCH_PARENT content gives the weighted WebView a bounded height.
        window?.setLayout(-1, -1)
        webView.loadUrl(initialUrl)
    }
    private fun showProblem(value: String) { message.text = value; progress.visibility = View.GONE }
    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()
    override fun dismiss() {
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.dismiss()
    }
}
