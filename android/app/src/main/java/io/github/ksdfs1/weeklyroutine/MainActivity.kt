package io.github.ksdfs1.weeklyroutine

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * The phone app: the web app (same index.html as on the PC) in a WebView, plus a small bridge so the
 * page can reach the native side — notification settings and permissions, the shared edit token,
 * pushing a just-saved routine straight to the widget/alarms, and saving the JSON export.
 *
 * The bridge is the `WRNative` object (androidx.webkit web message listener), only injected into
 * pages from [APP_ORIGIN]. The page sends {id, op, args} as JSON; we answer {id, result}, and send
 * {event} for things that happen on our side (permission answered, app resumed).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var errorBox: LinearLayout
    private var reply: JavaScriptReplyProxy? = null
    private var mainFrameFailed = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: Pair<Int, String>? = null   // (request id, JSON text) waiting for a save location

    private val prefs by lazy { Prefs.of(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // debug builds (the sideloaded APK): inspectable from a PC's chrome://inspect over USB
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this).apply {
            setBackgroundColor(getColor(R.color.bg))   // no white flash while the page loads
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true           // the page keeps its cache and token in localStorage
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = Client()
            webChromeClient = Chrome()
        }
        errorBox = buildErrorBox()
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(errorBox, FrameLayout.LayoutParams(-1, -1))
            // Android 15 draws edge-to-edge: keep the page clear of the status/navigation bars and the keyboard
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                }
                insets
            }
        }
        setContentView(root)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "WRNative", setOf(APP_ORIGIN)) { _, message, _, isMainFrame, replyProxy ->
                if (!isMainFrame) return@addWebMessageListener
                reply = replyProxy
                message.data?.let { handle(it, replyProxy) }
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(RoutineRepository.APP_URL)

        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        if (RoutineRepository.load(this) == null || RoutineRepository.isStale(this)) SyncWorker.syncNow(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // permissions may have been changed in system settings meanwhile
        sendEvent("resume")
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        sendEvent("permissions")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_PICK -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            REQ_SAVE -> {
                val (id, text) = pendingExport ?: return
                pendingExport = null
                val uri = data?.data
                val ok = resultCode == RESULT_OK && uri != null && try {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) {
                    false
                }
                respond(id, JSONObject().put("ok", ok).put("cancelled", resultCode != RESULT_OK))
            }
            else -> @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /* ---------------- bridge ---------------- */

    private fun handle(raw: String, proxy: JavaScriptReplyProxy) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }
        val id = msg.optInt("id")
        val args = msg.optJSONObject("args") ?: JSONObject()
        val result: Any? = when (msg.optString("op")) {
            "getSettings" -> JSONObject().apply {
                for ((name, key) in SETTINGS) put(name, prefs.getBoolean(key, true))
            }
            "setSetting" -> {
                val key = SETTINGS[args.optString("key")]
                if (key != null) prefs.edit().putBoolean(key, args.optBoolean("value")).apply()
                key != null
            }
            "getPermissions" -> permissions()
            "requestNotificationPermission" -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                else openNotificationSettings()
                true
            }
            "openExactAlarmSettings" -> {
                if (Build.VERSION.SDK_INT >= 31) tryStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                true
            }
            "openBatterySettings" -> tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            "openNotificationSettings" -> openNotificationSettings()
            "sendTestNotification" -> {
                val routine = RoutineRepository.load(this)
                when {
                    !Notifier.canPost(this) -> JSONObject().put("ok", false).put("reason", "permission")
                    routine == null -> JSONObject().put("ok", false).put("reason", "no-data")
                    else -> {
                        Notifier.postStatus(this, routine, routine.nowStatus(), test = true)
                        JSONObject().put("ok", true)
                    }
                }
            }
            "getInfo" -> JSONObject()
                .put("lastSync", RoutineRepository.lastSync(this))
                .put("version", packageManager.getPackageInfo(packageName, 0).versionName)
            // the page just loaded or saved the routine: the widget and alarms follow right away
            "onState" -> {
                val json = args.optString("json")
                if (Routine.parse(json) != null) {
                    Thread {
                        if (RoutineRepository.store(this, json)) SyncWorker.afterSync(this)
                    }.start()
                    true
                } else false
            }
            "getToken" -> RoutineRepository.token(this)
            "setToken" -> {
                prefs.edit().putString(Prefs.TOKEN, args.optString("token").trim()).apply()
                true
            }
            "saveFile" -> {
                pendingExport = id to args.optString("content")
                val pick = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, args.optString("name", "weekly-routine.json"))
                try {
                    @Suppress("DEPRECATION") startActivityForResult(pick, REQ_SAVE)
                    return   // answered in onActivityResult
                } catch (e: ActivityNotFoundException) {
                    pendingExport = null
                    JSONObject().put("ok", false)
                }
            }
            else -> null
        }
        proxy.postMessage(JSONObject().put("id", id).put("result", result ?: JSONObject.NULL).toString())
    }

    private fun respond(id: Int, result: Any) {
        reply?.postMessage(JSONObject().put("id", id).put("result", result).toString())
    }

    private fun sendEvent(name: String) {
        reply?.postMessage(JSONObject().put("event", name).toString())
    }

    private fun permissions() = JSONObject()
        .put("notifications", Notifier.canPost(this))
        .put("exactAlarm", TransitionScheduler.canExact(this))
        .put("battery", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))

    private fun openNotificationSettings(): Boolean =
        tryStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent); true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /* ---------------- WebView plumbing ---------------- */

    private inner class Client : WebViewClient() {
        // the app's own pages stay here; anything else opens in the browser
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "https" && url.host == APP_HOST && (url.path ?: "").startsWith(APP_PATH)) return false
            tryStart(Intent(Intent.ACTION_VIEW, url))
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            mainFrameFailed = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            errorBox.visibility = if (mainFrameFailed) View.VISIBLE else View.GONE
        }

        // first launch without internet (later launches come from the page's offline cache)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                errorBox.visibility = View.VISIBLE
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        // "JSON 가져오기": the page's <input type=file>
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                @Suppress("DEPRECATION") startActivityForResult(params.createIntent(), REQ_PICK)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    private fun buildErrorBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(32), dp(32), dp(32))
        setBackgroundColor(getColor(R.color.bg))
        visibility = View.GONE
        isClickable = true
        addView(TextView(context).apply {
            text = "앱을 불러오지 못했어요"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text))
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "처음 한 번은 인터넷 연결이 필요해요. 연결을 확인한 뒤 다시 시도해주세요."
            textSize = 13f
            setTextColor(getColor(R.color.text_mute))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        })
        addView(Button(context).apply {
            text = "다시 시도"
            isAllCaps = false
            setTextColor(getColor(R.color.accent_ink))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.accent))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                errorBox.visibility = View.GONE
                web.reload()
            }
        }, LinearLayout.LayoutParams(dp(160), dp(44)))
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val APP_HOST = "ksdfs1.github.io"
        private const val APP_PATH = "/Weekly-Routine"
        private const val APP_ORIGIN = "https://$APP_HOST"
        private const val REQ_NOTIFY = 1
        private const val REQ_PICK = 2
        private const val REQ_SAVE = 3

        /** setting names the page uses → SharedPreferences keys */
        private val SETTINGS = mapOf(
            "notify" to Prefs.NOTIFY,
            "sound" to Prefs.SOUND,
            "vibrate" to Prefs.VIBRATE,
            "notifyGaps" to Prefs.NOTIFY_GAPS,
        )
    }
}
