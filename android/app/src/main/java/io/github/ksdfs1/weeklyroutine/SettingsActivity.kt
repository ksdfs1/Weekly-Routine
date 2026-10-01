package io.github.ksdfs1.weeklyroutine

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** What the launcher icon opens: notification settings, permissions and a manual sync. */
class SettingsActivity : Activity() {

    private lateinit var nowText: TextView
    private lateinit var syncText: TextView
    private lateinit var permBox: LinearLayout
    private val dependentSwitches = mutableListOf<Switch>()

    private val prefs by lazy { Prefs.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            addView(col)
            // Android 15 draws apps edge-to-edge: keep the content clear of the status/navigation bars
            if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

        col.addView(text("Weekly Routine", 26f, R.color.text, bold = true))

        val nowCard = card()
        nowText = text("", 15f, R.color.text, bold = true)
        syncText = text("", 12f, R.color.text_mute)
        nowCard.addView(nowText)
        nowCard.addView(syncText)
        col.addView(nowCard)

        col.addView(section("알림"))
        val notifyCard = card()
        val master = switch("상태 변경 알림", "루틴상 지금 할 일이 바뀌면 알려줘요", Prefs.NOTIFY) { updateDependents() }
        notifyCard.addView(master)
        notifyCard.addView(switch("소리", null, Prefs.SOUND).also { dependentSwitches += it })
        notifyCard.addView(switch("진동", null, Prefs.VIBRATE).also { dependentSwitches += it })
        notifyCard.addView(switch("미지정 시간 시작도 알림", "블록이 끝나고 빈 시간이 시작될 때", Prefs.NOTIFY_GAPS).also { dependentSwitches += it })
        notifyCard.addView(button("테스트 알림 보내기") { sendTest() })
        col.addView(notifyCard)
        updateDependents()

        col.addView(section("권한"))
        permBox = card()
        col.addView(permBox)

        col.addView(section("동기화"))
        val syncCard = card()
        syncCard.addView(text("웹앱에서 저장한 루틴을 1시간마다 불러와요. 방금 바꿨다면 지금 동기화를 눌러주세요.", 12f, R.color.text_mute))
        syncCard.addView(button("지금 동기화") { syncNow(showToast = true) })
        syncCard.addView(button("웹앱 열기 (편집)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RoutineRepository.APP_URL)))
        })
        col.addView(syncCard)

        col.addView(section("위젯에서 케이스 선택"))
        val tokenCard = card()
        tokenCard.addView(text("위젯 위쪽의 '요일 · 케이스 ▾'를 누르면 오늘 케이스를 바꿀 수 있어요. 웹앱의 편집 토큰을 넣어두면 바꾼 케이스가 서버에 저장돼 웹앱·PC 위젯에도 똑같이 반영되고, 비워두면 이 기기에서만 적용돼요.", 12f, R.color.text_mute))
        val tokenInput = EditText(this).apply {
            setText(RoutineRepository.token(this@SettingsActivity))
            hint = "편집 토큰"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            textSize = 15f
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text_mute))
        }
        tokenCard.addView(tokenInput)
        tokenCard.addView(button("토큰 저장") {
            val t = tokenInput.text.toString().trim()
            prefs.edit().putString(Prefs.TOKEN, t).apply()
            toast(if (t.isEmpty()) "토큰을 지웠어요. 위젯에서 바꾼 케이스는 이 기기에만 적용돼요." else "토큰을 저장했어요")
        })
        col.addView(tokenCard)

        col.addView(text("홈 화면을 길게 누르고 위젯 → Weekly Routine → '오늘 루틴'(4x2)을 추가하세요.", 12f, R.color.text_mute).apply {
            setPadding(0, dp(16), 0, 0)
        })

        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderPermissions()
        if (RoutineRepository.load(this) == null || RoutineRepository.isStale(this)) syncNow(showToast = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        renderPermissions()
    }

    private fun renderStatus() {
        val routine = RoutineRepository.load(this)
        nowText.text = if (routine == null) "아직 루틴을 불러오지 못했어요" else {
            val st = routine.nowStatus()
            "지금: " + st.name + " · " + Routine.fmtMin(st.end) + "까지"
        }
        val last = RoutineRepository.lastSync(this)
        syncText.text = if (last == 0L) "동기화한 적 없음" else
            "마지막 동기화: " + DateFormat.format("M/d HH:mm", last)
    }

    private fun renderPermissions() {
        permBox.removeAllViews()
        var allGood = true
        if (!Notifier.canPost(this)) {
            allGood = false
            permBox.addView(text("알림 권한이 꺼져 있어요.", 13f, R.color.text))
            permBox.addView(button("알림 권한 허용") {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                else openAppNotificationSettings()
            })
        }
        if (!TransitionScheduler.canExact(this)) {
            allGood = false
            permBox.addView(text("정확한 알람 권한이 없어서 알림이 몇 분 늦을 수 있어요.", 13f, R.color.text))
            permBox.addView(button("정확한 알람 허용") {
                if (Build.VERSION.SDK_INT >= 31) startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            })
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            allGood = false
            permBox.addView(text("배터리 최적화 때문에 알림이 늦거나 빠질 수 있어요. 목록에서 Weekly Routine을 '제한 없음'으로 바꿔주세요.", 13f, R.color.text))
            permBox.addView(button("배터리 최적화 설정 열기") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
        }
        if (allGood) permBox.addView(text("필요한 권한이 모두 허용돼 있어요.", 13f, R.color.text_dim))
        permBox.addView(button("시스템 알림 설정 열기") { openAppNotificationSettings() })
    }

    private fun openAppNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun sendTest() {
        val routine = RoutineRepository.load(this)
        when {
            !Notifier.canPost(this) -> toast("알림 권한을 먼저 허용해주세요")
            routine == null -> toast("먼저 동기화해주세요")
            else -> Notifier.postStatus(this, routine, routine.nowStatus(), test = true)
        }
    }

    private fun syncNow(showToast: Boolean) {
        Thread {
            val ok = RoutineRepository.fetch(this)
            if (ok) SyncWorker.afterSync(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderStatus()
                if (showToast) toast(if (ok) "최신 루틴을 불러왔어요" else "불러오지 못했어요. 인터넷 연결을 확인해주세요.")
            }
        }.start()
    }

    private fun updateDependents() {
        val on = prefs.getBoolean(Prefs.NOTIFY, true)
        dependentSwitches.forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.4f }
    }

    /* ---- tiny view helpers (no AppCompat/Material dependency) ---- */

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun section(title: String) = text(title, 13f, R.color.text_dim, bold = true).apply {
        setPadding(dp(4), dp(22), 0, dp(8))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.surface))
            setStroke(dp(1), getColor(R.color.border_soft))
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun switch(title: String, hint: String?, key: String, onChange: () -> Unit = {}) = Switch(this).apply {
        text = if (hint == null) title else "$title\n$hint"
        textSize = 15f
        setTextColor(getColor(R.color.text))
        setPadding(0, dp(8), 0, dp(8))
        isChecked = prefs.getBoolean(key, true)
        setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            onChange()
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(getColor(R.color.accent_ink))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.accent))
            cornerRadius = dp(10).toFloat()
        }
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
