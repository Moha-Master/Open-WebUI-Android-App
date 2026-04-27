package cafe.jiahui.openwebui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import cafe.jiahui.openwebui.utils.NetworkUtils
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val activeProfileUrlKey = "ACTIVE_PROFILE_URL"
    private val prefsName = "OpenWebUIPrefs"
    private val settingsFileName = "url_settings.json"

    private var currentResolvedTarget: ResolvedTarget? = null
    private var hasAttemptedFallback = false
    private var activeProfileUrl: String? = null
    private var isShowingErrorDialog = false
    private var settingsDialog: AlertDialog? = null
    private var pendingWebViewState: Bundle? = null
    private var networkCallbackRegistered = false
    private var pageLoadTimeoutRunnable: Runnable? = null
    private var pageLoadTimedOut = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChangeEvent()
        }

        override fun onLost(network: Network) {
            handleNetworkChangeEvent()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: android.net.NetworkCapabilities) {
            handleNetworkChangeEvent()
        }
    }

    private val permissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private enum class UrlSource {
        DEFAULT,
        MOBILE,
        VPN,
        WIFI,
        FALLBACK_DEFAULT
    }

    private data class WifiRule(
        val ssid: String,
        val url: String
    )

    private enum class RetentionUnit {
        SECOND,
        MINUTE,
        HOUR,
        DAY,
        PERMANENT
    }

    private data class RetentionPolicy(
        val value: Int,
        val unit: RetentionUnit
    )

    private data class RetentionEditor(
        val valueEdit: TextInputEditText,
        val unitSpinner: Spinner
    )

    private data class StorageCategoryStats(
        val sizeBytes: Long,
        val count: Int
    )

    private data class StorageStats(
        val cache: StorageCategoryStats,
        val localStorage: StorageCategoryStats,
        val cookies: StorageCategoryStats
    )

    private data class UrlSettings(
        val defaultUrl: String,
        val mobileUrl: String?,
        val vpnUrl: String?,
        val wifiRules: List<WifiRule>,
        val autoFallbackToDefault: Boolean,
        val defaultTimeoutSec: Int,
        val mobileTimeoutSec: Int,
        val vpnTimeoutSec: Int,
        val wifiTimeoutSec: Int,
        val cacheRetention: RetentionPolicy,
        val localStorageRetention: RetentionPolicy,
        val cookieRetention: RetentionPolicy,
        val cacheRecords: MutableMap<String, Long>,
        val localStorageRecords: MutableMap<String, Long>,
        val cookieRecords: MutableMap<String, Long>
    )

    private data class ResolvedTarget(
        val url: String,
        val source: UrlSource,
        val wifiSsid: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setStatusBarForTheme()
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        activeProfileUrl = getPrefs().getString(activeProfileUrlKey, null)
        pendingWebViewState = savedInstanceState?.getBundle(WEBVIEW_STATE_KEY)

        requestPermissions()
    }

    private fun setStatusBarForTheme() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val window = this.window
            val decorView = window.decorView

            if (isDarkMode) {
                window.statusBarColor = Color.parseColor("#171717")
                decorView.systemUiVisibility = 0
            } else {
                window.statusBarColor = getColor(android.R.color.white)
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        } else {
            val window = this.window
            window.statusBarColor = getColor(android.R.color.black)
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grantResults ->
                var allGranted = true
                for ((permission, result) in grantResults) {
                    if (!result) {
                        allGranted = false
                        if (!shouldShowRequestPermissionRationale(permission)) {
                            showPermissionDeniedDialog(permission)
                        }
                    }
                }
                if (!allGranted) {
                    Toast.makeText(this, "部分权限被拒绝，可能影响某些功能使用", Toast.LENGTH_LONG).show()
                }
                initializeWebView()
            }
            launcher.launch(permissionsToRequest.toTypedArray())
        } else {
            initializeWebView()
        }
    }

    private fun showPermissionDeniedDialog(permission: String) {
        val permissionDesc = when (permission) {
            Manifest.permission.CAMERA -> "相机"
            Manifest.permission.RECORD_AUDIO -> "麦克风"
            Manifest.permission.ACCESS_FINE_LOCATION -> "位置"
            else -> "此"
        }

        val message = "应用需要${permissionDesc}权限才能正常使用相应功能。请前往设置手动开启。"
        val contextWrapper = ContextThemeWrapper(this, R.style.Theme_OpenWebUI_Dialog)

        AlertDialog.Builder(contextWrapper)
            .setTitle("需要权限")
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(false)
            .show()
    }

    private fun initializeWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "OpenWebUI-Android-1.0"
            mediaPlaybackRequiresUserGesture = false
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cancelPageLoadTimeout()
                markCurrentUrlDataRecords(url)
                syncThemeToWeb()
                injectAppSettingsEntry()
                isShowingErrorDialog = false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "openwebui-android") {
                    if (uri.host == "settings") {
                        showUrlSettingsDialog()
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    cancelPageLoadTimeout()
                    handleMainFrameLoadFailure(error?.description?.toString() ?: "未知错误")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                    cancelPageLoadTimeout()
                    handleMainFrameLoadFailure("HTTP错误: ${errorResponse?.statusCode}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                val requiredPermissions = mutableListOf<String>()
                val acceptTypes = fileChooserParams.acceptTypes ?: arrayOf()
                var needCameraPermission = false
                var needMicrophonePermission = false

                for (acceptType in acceptTypes) {
                    if (acceptType.contains("image") || acceptType.contains("picture") || acceptType.contains("photo")) {
                        needCameraPermission = true
                    }
                    if (acceptType.contains("audio") || acceptType.contains("sound") || acceptType.contains("voice")) {
                        needMicrophonePermission = true
                    }
                }

                if (needCameraPermission && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requiredPermissions.add(Manifest.permission.CAMERA)
                }

                if (needMicrophonePermission && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requiredPermissions.add(Manifest.permission.RECORD_AUDIO)
                }

                if (requiredPermissions.isNotEmpty()) {
                    this@MainActivity.filePathCallback = filePathCallback
                    permissionForFilePickerLauncher.launch(requiredPermissions.toTypedArray())
                    return true
                }

                this@MainActivity.filePathCallback = filePathCallback
                openFileChooser()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val requestedPermissions = request?.resources ?: arrayOf()
                pendingPermissionRequest = request

                val permissionsToRequest = mutableListOf<String>()
                for (resource in requestedPermissions) {
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                permissionsToRequest.add(Manifest.permission.CAMERA)
                            }
                        }

                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                            }
                        }

                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> Unit
                    }
                }

                if (permissionsToRequest.isNotEmpty()) {
                    requestLauncher.launch(permissionsToRequest.toTypedArray())
                } else {
                    request?.grant(requestedPermissions)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(
                    "WebView",
                    "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}"
                )
                return super.onConsoleMessage(consoleMessage)
            }
        }

        if (restoreWebViewStateIfNeeded()) {
            return
        }

        checkAndLoadServerUrl()
    }

    private fun restoreWebViewStateIfNeeded(): Boolean {
        val state = pendingWebViewState ?: return false
        pendingWebViewState = null
        val restored = webView.restoreState(state)
        if (restored != null && restored.size > 0) {
            val restoredUrl = webView.url
            if (!restoredUrl.isNullOrBlank()) {
                val processedUrl = processUrl(restoredUrl)
                activeProfileUrl = processedUrl
                currentResolvedTarget = ResolvedTarget(processedUrl, UrlSource.DEFAULT)
                getPrefs().edit().putString(activeProfileUrlKey, processedUrl).apply()
            }
            return true
        }
        return false
    }

    private fun checkAndLoadServerUrl() {
        val settings = loadUrlSettings()
        if (settings.defaultUrl.isBlank()) {
            showUrlSettingsDialog(forceRequired = true)
        } else {
            resolveAndLoadByNetwork(forceReload = true)
        }
    }

    private fun showUrlSettingsDialog(forceRequired: Boolean = false) {
        if (settingsDialog?.isShowing == true) {
            return
        }

        val settings = loadUrlSettings()
        val contextWrapper = ContextThemeWrapper(this, R.style.Theme_OpenWebUI_Dialog)

        val rootLayout = LinearLayout(contextWrapper).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val wifiRules = settings.wifiRules.toMutableList()

        fun addSectionTitle(text: String, topMargin: Int = 24) {
            val title = TextView(contextWrapper).apply {
                this.text = text
                textSize = 16f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = topMargin
                layoutParams = params
            }
            rootLayout.addView(title)
        }

        fun addSubSectionTitle(text: String, topMargin: Int = 16) {
            val title = TextView(contextWrapper).apply {
                this.text = text
                textSize = 14f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = topMargin
                layoutParams = params
            }
            rootLayout.addView(title)
        }

        fun addUrlInput(hint: String, value: String?, topMargin: Int = 12): TextInputEditText {
            val layout = TextInputLayout(contextWrapper).apply {
                this.hint = hint
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = topMargin
                layoutParams = params
            }
            val edit = TextInputEditText(contextWrapper).apply {
                setText(value ?: "")
            }
            layout.addView(edit)
            rootLayout.addView(layout)
            return edit
        }

        fun addNumberInput(hint: String, value: Int, topMargin: Int = 12): TextInputEditText {
            val layout = TextInputLayout(contextWrapper).apply {
                this.hint = hint
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = topMargin
                layoutParams = params
            }
            val edit = TextInputEditText(contextWrapper).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }
            layout.addView(edit)
            rootLayout.addView(layout)
            return edit
        }

        addSectionTitle("连接设置", topMargin = 0)
        addSubSectionTitle("URL设置", topMargin = 10)

        val defaultUrlEdit = addUrlInput("默认URL（必填）", settings.defaultUrl)

        val fallbackSwitch = SwitchMaterial(contextWrapper).apply {
            text = "移动数据/VPN/WiFi规则 URL 不可用时自动回退默认 URL"
            isChecked = settings.autoFallbackToDefault
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 12
            layoutParams = params
        }
        rootLayout.addView(fallbackSwitch)

        val mobileUrlEdit = addUrlInput("移动数据 URL（可选）", settings.mobileUrl)
        val vpnUrlEdit = addUrlInput("VPN URL（可选）", settings.vpnUrl)

        addSubSectionTitle("WiFi规则", topMargin = 14)

        val wifiRulesContainer = LinearLayout(contextWrapper).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 8
            layoutParams = params
        }
        rootLayout.addView(wifiRulesContainer)

        lateinit var refreshWifiRules: () -> Unit
        refreshWifiRules = wifiRefresh@{
            wifiRulesContainer.removeAllViews()

            val addButton = Button(contextWrapper).apply {
                text = "添加WiFi规则"
                setOnClickListener {
                    showWifiRuleEditDialog(contextWrapper, null) { created ->
                        val existingIndex = wifiRules.indexOfFirst { it.ssid.equals(created.ssid, ignoreCase = true) }
                        if (existingIndex >= 0) {
                            wifiRules[existingIndex] = created
                        } else {
                            wifiRules.add(created)
                        }
                        refreshWifiRules()
                    }
                }
            }
            wifiRulesContainer.addView(addButton)

            if (wifiRules.isEmpty()) {
                val empty = TextView(contextWrapper).apply {
                    text = "暂无WiFi规则"
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.topMargin = 8
                    layoutParams = params
                }
                wifiRulesContainer.addView(empty)
                return@wifiRefresh
            }

            wifiRules.forEachIndexed { index, rule ->
                val row = LinearLayout(contextWrapper).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.topMargin = 10
                    layoutParams = params
                }

                val textColumn = LinearLayout(contextWrapper).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val title = TextView(contextWrapper).apply {
                    text = rule.ssid
                    textSize = 14f
                }
                val subtitle = TextView(contextWrapper).apply {
                    text = rule.url
                    textSize = 12f
                }
                textColumn.addView(title)
                textColumn.addView(subtitle)

                val actions = LinearLayout(contextWrapper).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val editButton = Button(contextWrapper).apply {
                    text = "编辑"
                    setOnClickListener {
                        showWifiRuleEditDialog(contextWrapper, rule) { updated ->
                            wifiRules[index] = updated
                            refreshWifiRules()
                        }
                    }
                }
                val deleteButton = Button(contextWrapper).apply {
                    text = "删除"
                    setOnClickListener {
                        AlertDialog.Builder(contextWrapper)
                            .setTitle("删除确认")
                            .setMessage("确认删除 WiFi 规则 ${rule.ssid} ?")
                            .setPositiveButton("删除") { _, _ ->
                                wifiRules.removeAt(index)
                                refreshWifiRules()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }

                actions.addView(editButton)
                actions.addView(deleteButton)
                row.addView(textColumn)
                row.addView(actions)
                wifiRulesContainer.addView(row)
            }
        }
        refreshWifiRules()

        addSubSectionTitle("超时设置", topMargin = 20)
        val defaultTimeoutEdit = addNumberInput("默认超时（秒）", settings.defaultTimeoutSec)
        val mobileTimeoutEdit = addNumberInput("移动数据超时（秒）", settings.mobileTimeoutSec)
        val vpnTimeoutEdit = addNumberInput("VPN超时（秒）", settings.vpnTimeoutSec)
        val wifiTimeoutEdit = addNumberInput("WiFi规则超时（秒）", settings.wifiTimeoutSec)

        addSectionTitle("本地存储设置")
        addSubSectionTitle("保存时长", topMargin = 10)

        val cacheRetentionEditor = addRetentionEditor(contextWrapper, rootLayout, "页面缓存", settings.cacheRetention)
        val localStorageRetentionEditor = addRetentionEditor(contextWrapper, rootLayout, "LocalStorage", settings.localStorageRetention)
        val cookieRetentionEditor = addRetentionEditor(contextWrapper, rootLayout, "Cookies", settings.cookieRetention)

        addSubSectionTitle("清除", topMargin = 20)

        val cacheStatsText = TextView(contextWrapper)
        val localStorageStatsText = TextView(contextWrapper)
        val cookieStatsText = TextView(contextWrapper)

        val clearCacheButton = Button(contextWrapper).apply { text = "清除页面缓存" }
        val clearLoginButton = Button(contextWrapper).apply { text = "清除登录数据" }
        val clearLocalStorageButton = Button(contextWrapper).apply { text = "清除LocalStorage" }
        val clearAllButton = Button(contextWrapper).apply { text = "一键清除三项" }

        fun addClearRow(label: String, statsView: TextView, actionButton: Button) {
            val row = LinearLayout(contextWrapper).apply {
                orientation = LinearLayout.VERTICAL
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 10
                layoutParams = params
            }
            val labelView = TextView(contextWrapper).apply {
                text = label
                textSize = 13f
            }
            row.addView(labelView)
            row.addView(statsView)
            row.addView(actionButton)
            rootLayout.addView(row)
        }

        addClearRow("页面缓存", cacheStatsText, clearCacheButton)
        addClearRow("LocalStorage", localStorageStatsText, clearLocalStorageButton)
        addClearRow("Cookies/登录数据", cookieStatsText, clearLoginButton)

        clearAllButton.apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 10
            layoutParams = params
        }
        rootLayout.addView(clearAllButton)

        fun refreshStorageStats() {
            val latestSettings = loadUrlSettings()
            val stats = calculateStorageStats(latestSettings)
            cacheStatsText.text = formatStorageStatText(stats.cache)
            localStorageStatsText.text = formatStorageStatText(stats.localStorage)
            cookieStatsText.text = formatStorageStatText(stats.cookies)
        }

        clearCacheButton.setOnClickListener {
            val latest = loadUrlSettings()
            clearPageCacheData(latest)
            refreshStorageStats()
            Toast.makeText(this, "页面缓存已清除", Toast.LENGTH_SHORT).show()
        }

        clearLocalStorageButton.setOnClickListener {
            val latest = loadUrlSettings()
            clearLocalStorageData(latest)
            refreshStorageStats()
            Toast.makeText(this, "LocalStorage已清除", Toast.LENGTH_SHORT).show()
        }

        clearLoginButton.setOnClickListener {
            val latest = loadUrlSettings()
            clearLoginData(latest) {
                refreshStorageStats()
                Toast.makeText(this, "登录数据已清除", Toast.LENGTH_SHORT).show()
            }
        }

        clearAllButton.setOnClickListener {
            val latest = loadUrlSettings()
            clearAllStorageData(latest) {
                refreshStorageStats()
                Toast.makeText(this, "缓存/LocalStorage/Cookies 已全部清除", Toast.LENGTH_SHORT).show()
            }
        }

        refreshStorageStats()

        val scrollView = ScrollView(contextWrapper).apply {
            addView(rootLayout)
        }

        val dialog = AlertDialog.Builder(contextWrapper)
            .setTitle("App设置")
            .setView(scrollView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消") { dialogInterface, _ ->
                if (forceRequired && webView.url.isNullOrBlank()) {
                    dialogInterface.dismiss()
                    finish()
                } else {
                    dialogInterface.dismiss()
                }
            }
            .setCancelable(!forceRequired)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val defaultUrlInput = defaultUrlEdit.text?.toString()?.trim().orEmpty()
                if (defaultUrlInput.isBlank()) {
                    Toast.makeText(this, "默认URL不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val processedDefault = processUrl(defaultUrlInput)
                if (!validateUrl(processedDefault)) {
                    Toast.makeText(this, "默认URL无效", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val mobileRaw = mobileUrlEdit.text?.toString()?.trim().orEmpty()
                val processedMobile = if (mobileRaw.isBlank()) {
                    null
                } else {
                    parseOptionalUrlOrToast(mobileRaw, "移动数据") ?: return@setOnClickListener
                }

                val vpnRaw = vpnUrlEdit.text?.toString()?.trim().orEmpty()
                val processedVpn = if (vpnRaw.isBlank()) {
                    null
                } else {
                    parseOptionalUrlOrToast(vpnRaw, "VPN") ?: return@setOnClickListener
                }

                val defaultTimeout = parseTimeoutInput(defaultTimeoutEdit, "默认超时") ?: return@setOnClickListener
                val mobileTimeout = parseTimeoutInput(mobileTimeoutEdit, "移动数据超时") ?: return@setOnClickListener
                val vpnTimeout = parseTimeoutInput(vpnTimeoutEdit, "VPN超时") ?: return@setOnClickListener
                val wifiTimeout = parseTimeoutInput(wifiTimeoutEdit, "WiFi超时") ?: return@setOnClickListener

                val cacheRetention = readRetentionPolicy(cacheRetentionEditor, "页面缓存") ?: return@setOnClickListener
                val localStorageRetention = readRetentionPolicy(localStorageRetentionEditor, "LocalStorage") ?: return@setOnClickListener
                val cookieRetention = readRetentionPolicy(cookieRetentionEditor, "Cookies") ?: return@setOnClickListener

                val latest = loadUrlSettings()
                val newSettings = latest.copy(
                    defaultUrl = processedDefault,
                    mobileUrl = processedMobile,
                    vpnUrl = processedVpn,
                    wifiRules = wifiRules.toList(),
                    autoFallbackToDefault = fallbackSwitch.isChecked,
                    defaultTimeoutSec = defaultTimeout,
                    mobileTimeoutSec = mobileTimeout,
                    vpnTimeoutSec = vpnTimeout,
                    wifiTimeoutSec = wifiTimeout,
                    cacheRetention = cacheRetention,
                    localStorageRetention = localStorageRetention,
                    cookieRetention = cookieRetention
                )

                saveUrlSettings(newSettings)
                enforceRetentionPolicies(newSettings)
                resolveAndLoadByNetwork(forceReload = true)
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            settingsDialog = null
        }
        settingsDialog = dialog
        dialog.show()
    }

    private fun showWifiRuleEditDialog(
        contextWrapper: ContextThemeWrapper,
        initialRule: WifiRule?,
        onSave: (WifiRule) -> Unit
    ) {
        val root = LinearLayout(contextWrapper).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val ssidLayout = TextInputLayout(contextWrapper).apply {
            hint = "WiFi名称"
        }
        val ssidEdit = TextInputEditText(contextWrapper).apply {
            setText(initialRule?.ssid ?: "")
        }
        ssidLayout.addView(ssidEdit)
        root.addView(ssidLayout)

        val urlLayout = TextInputLayout(contextWrapper).apply {
            hint = "对应URL"
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 12
            layoutParams = params
        }
        val urlEdit = TextInputEditText(contextWrapper).apply {
            setText(initialRule?.url ?: "")
        }
        urlLayout.addView(urlEdit)
        root.addView(urlLayout)

        val dialog = AlertDialog.Builder(contextWrapper)
            .setTitle(if (initialRule == null) "添加WiFi规则" else "编辑WiFi规则")
            .setView(root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = normalizeSsid(ssidEdit.text?.toString()?.trim().orEmpty())
                val urlRaw = urlEdit.text?.toString()?.trim().orEmpty()

                if (ssid.isBlank()) {
                    Toast.makeText(this, "WiFi名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (urlRaw.isBlank()) {
                    Toast.makeText(this, "URL不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val processedUrl = processUrl(urlRaw)
                if (!validateUrl(processedUrl)) {
                    Toast.makeText(this, "URL无效", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                onSave(WifiRule(ssid, processedUrl))
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun addRetentionEditor(
        contextWrapper: ContextThemeWrapper,
        rootLayout: LinearLayout,
        label: String,
        initialPolicy: RetentionPolicy
    ): RetentionEditor {
        val row = LinearLayout(contextWrapper).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 10
            layoutParams = params
        }

        val valueLayout = TextInputLayout(contextWrapper).apply {
            hint = "$label 保存时长"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueEdit = TextInputEditText(contextWrapper).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(initialPolicy.value.toString())
        }
        valueLayout.addView(valueEdit)

        val unitSpinner = Spinner(contextWrapper).apply {
            val adapter = ArrayAdapter(
                contextWrapper,
                android.R.layout.simple_spinner_item,
                listOf("秒", "分钟", "小时", "天", "永久")
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            setSelection(retentionUnitToIndex(initialPolicy.unit))
        }

        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val unit = retentionUnitFromIndex(position)
                if (unit == RetentionUnit.PERMANENT) {
                    valueEdit.setText("0")
                    valueEdit.isEnabled = false
                } else {
                    valueEdit.isEnabled = true
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        row.addView(valueLayout)
        row.addView(unitSpinner)
        rootLayout.addView(row)

        return RetentionEditor(valueEdit, unitSpinner)
    }

    private fun retentionUnitToIndex(unit: RetentionUnit): Int {
        return when (unit) {
            RetentionUnit.SECOND -> 0
            RetentionUnit.MINUTE -> 1
            RetentionUnit.HOUR -> 2
            RetentionUnit.DAY -> 3
            RetentionUnit.PERMANENT -> 4
        }
    }

    private fun retentionUnitFromIndex(index: Int): RetentionUnit {
        return when (index) {
            0 -> RetentionUnit.SECOND
            1 -> RetentionUnit.MINUTE
            2 -> RetentionUnit.HOUR
            3 -> RetentionUnit.DAY
            else -> RetentionUnit.PERMANENT
        }
    }

    private fun readRetentionPolicy(editor: RetentionEditor, label: String): RetentionPolicy? {
        val unit = retentionUnitFromIndex(editor.unitSpinner.selectedItemPosition)
        if (unit == RetentionUnit.PERMANENT) {
            return RetentionPolicy(0, RetentionUnit.PERMANENT)
        }

        val valueText = editor.valueEdit.text?.toString()?.trim().orEmpty()
        val value = valueText.toIntOrNull()
        if (value == null || value < 0) {
            Toast.makeText(this, "$label 保存时长需为非负整数", Toast.LENGTH_SHORT).show()
            return null
        }

        return RetentionPolicy(value, unit)
    }

    private fun parseOptionalUrlOrToast(raw: String, label: String): String? {
        val processed = processUrl(raw)
        if (!validateUrl(processed)) {
            Toast.makeText(this, "$label URL无效", Toast.LENGTH_SHORT).show()
            return null
        }
        return processed
    }

    private fun parseTimeoutInput(edit: TextInputEditText, label: String): Int? {
        val text = edit.text?.toString()?.trim().orEmpty()
        val value = text.toIntOrNull()
        if (value == null || value < 0) {
            Toast.makeText(this, "$label 需为非负整数", Toast.LENGTH_SHORT).show()
            return null
        }
        return value
    }

    private fun normalizeProfileUrl(url: String): String {
        return processUrl(url).trim().removeSuffix("/")
    }

    private fun webOrigin(url: String): String {
        return try {
            val uri = java.net.URI(processUrl(url))
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return normalizeProfileUrl(url)
            val defaultPort = when (scheme.lowercase(Locale.getDefault())) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
            val portPart = if (uri.port != -1 && uri.port != defaultPort) ":${uri.port}" else ""
            "$scheme://$host$portPart"
        } catch (_: Exception) {
            normalizeProfileUrl(url)
        }
    }

    private fun startPageLoadTimeout(timeoutSec: Int) {
        cancelPageLoadTimeout()
        if (timeoutSec <= 0) {
            return
        }

        pageLoadTimedOut = false
        val runnable = Runnable {
            pageLoadTimedOut = true
            handleMainFrameLoadFailure("请求超时（${timeoutSec}秒）")
        }
        pageLoadTimeoutRunnable = runnable
        uiHandler.postDelayed(runnable, timeoutSec * 1000L)
    }

    private fun cancelPageLoadTimeout() {
        pageLoadTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        pageLoadTimeoutRunnable = null
        pageLoadTimedOut = false
    }

    private fun markCurrentUrlDataRecords(url: String?) {
        if (url.isNullOrBlank()) {
            return
        }

        val profileUrl = normalizeProfileUrl(url)
        val origin = webOrigin(url)
        val now = System.currentTimeMillis()
        val settings = loadUrlSettings()

        settings.cacheRecords[profileUrl] = now
        settings.localStorageRecords[origin] = now
        saveUrlSettings(settings)
    }

    private fun removeCookieSnapshots(keys: Collection<String>? = null) {
        val prefs = getPrefs()
        val targetKeys = keys ?: prefs.all.keys.filter { it.startsWith("PROFILE_COOKIES_") }
        if (targetKeys.isEmpty()) {
            return
        }
        val editor = prefs.edit()
        targetKeys.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun retentionMillis(policy: RetentionPolicy): Long? {
        if (policy.unit == RetentionUnit.PERMANENT) {
            return null
        }
        if (policy.value <= 0) {
            return 0L
        }
        return when (policy.unit) {
            RetentionUnit.SECOND -> policy.value * 1000L
            RetentionUnit.MINUTE -> policy.value * 60_000L
            RetentionUnit.HOUR -> policy.value * 3_600_000L
            RetentionUnit.DAY -> policy.value * 86_400_000L
            RetentionUnit.PERMANENT -> null
        }
    }

    private fun pruneExpiredRecords(
        records: MutableMap<String, Long>,
        policy: RetentionPolicy,
        now: Long
    ): List<String> {
        val ttl = retentionMillis(policy) ?: return emptyList()
        if (ttl == 0L) {
            val all = records.keys.toList()
            records.clear()
            return all
        }

        val expired = records.filter { (_, time) -> now - time > ttl }.keys.toList()
        expired.forEach { records.remove(it) }
        return expired
    }

    private fun enforceRetentionPolicies(settings: UrlSettings) {
        val now = System.currentTimeMillis()
        var changed = false

        val expiredCache = pruneExpiredRecords(settings.cacheRecords, settings.cacheRetention, now)
        if (expiredCache.isNotEmpty()) {
            webView.clearCache(true)
            changed = true
        }

        val expiredLocalStorage = pruneExpiredRecords(settings.localStorageRecords, settings.localStorageRetention, now)
        if (expiredLocalStorage.isNotEmpty()) {
            expiredLocalStorage.forEach { origin ->
                try {
                    WebStorage.getInstance().deleteOrigin(origin)
                } catch (_: Exception) {
                }
            }
            changed = true
        }

        val expiredCookies = pruneExpiredRecords(settings.cookieRecords, settings.cookieRetention, now)
        if (expiredCookies.isNotEmpty()) {
            val keys = expiredCookies.map { cookieStoreKey(it) }
            removeCookieSnapshots(keys)
            if (retentionMillis(settings.cookieRetention) == 0L) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            changed = true
        }

        if (changed) {
            saveUrlSettings(settings)
        }
    }

    private fun calculateDirectorySize(file: File?): Long {
        if (file == null || !file.exists()) {
            return 0L
        }
        if (file.isFile) {
            return file.length()
        }
        return file.listFiles()?.sumOf { calculateDirectorySize(it) } ?: 0L
    }

    private fun calculateStorageStats(settings: UrlSettings): StorageStats {
        val appDataDir = File(applicationInfo.dataDir)
        val webViewDefaultDir = File(appDataDir, "app_webview/Default")

        val cacheSize = calculateDirectorySize(File(webViewDefaultDir, "Cache")) + calculateDirectorySize(cacheDir)
        val localStorageSize =
            calculateDirectorySize(File(webViewDefaultDir, "Local Storage")) +
                calculateDirectorySize(File(webViewDefaultDir, "IndexedDB"))

        val cookieSnapshots = getPrefs().all
            .filterKeys { it.startsWith("PROFILE_COOKIES_") }
            .values
            .mapNotNull { it as? String }
        val cookieSize = cookieSnapshots.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

        return StorageStats(
            cache = StorageCategoryStats(cacheSize, settings.cacheRecords.size),
            localStorage = StorageCategoryStats(localStorageSize, settings.localStorageRecords.size),
            cookies = StorageCategoryStats(cookieSize, settings.cookieRecords.size)
        )
    }

    private fun formatStorageStatText(stats: StorageCategoryStats): String {
        val sizeMb = stats.sizeBytes.toDouble() / 1024.0 / 1024.0
        return String.format(Locale.getDefault(), "大小: %.2f M | URL数量: %d", sizeMb, stats.count)
    }

    private fun clearPageCacheData(settings: UrlSettings) {
        webView.clearCache(true)
        settings.cacheRecords.clear()
        saveUrlSettings(settings)
    }

    private fun clearLocalStorageData(settings: UrlSettings) {
        WebStorage.getInstance().deleteAllData()
        settings.localStorageRecords.clear()
        saveUrlSettings(settings)
    }

    private fun clearLoginData(settings: UrlSettings, onDone: () -> Unit) {
        removeCookieSnapshots()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            settings.cookieRecords.clear()
            saveUrlSettings(settings)
            onDone()
        }
    }

    private fun clearAllStorageData(settings: UrlSettings, onDone: () -> Unit) {
        clearPageCacheData(settings)
        clearLocalStorageData(settings)
        clearLoginData(settings, onDone)
    }

    private fun validateUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            uri.scheme != null && uri.host != null
        } catch (_: Exception) {
            false
        }
    }

    private fun processUrl(url: String): String {
        return if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
    }

    private fun normalizeSsid(ssid: String): String {
        return ssid.removePrefix("\"").removeSuffix("\"").trim()
    }

    private fun getPrefs() = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun getSettingsFile(): File? {
        val dir = getExternalFilesDir(null) ?: return null
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, settingsFileName)
    }

    private fun defaultUrlSettings() = UrlSettings(
        defaultUrl = "",
        mobileUrl = null,
        vpnUrl = null,
        wifiRules = emptyList(),
        autoFallbackToDefault = true,
        defaultTimeoutSec = 25,
        mobileTimeoutSec = 25,
        vpnTimeoutSec = 25,
        wifiTimeoutSec = 25,
        cacheRetention = RetentionPolicy(0, RetentionUnit.PERMANENT),
        localStorageRetention = RetentionPolicy(0, RetentionUnit.PERMANENT),
        cookieRetention = RetentionPolicy(0, RetentionUnit.PERMANENT),
        cacheRecords = mutableMapOf(),
        localStorageRecords = mutableMapOf(),
        cookieRecords = mutableMapOf()
    )

    private fun parseRetentionPolicy(obj: JSONObject?, key: String, fallback: RetentionPolicy): RetentionPolicy {
        val node = obj?.optJSONObject(key) ?: return fallback
        val value = node.optInt("value", fallback.value).coerceAtLeast(0)
        val unit = try {
            RetentionUnit.valueOf(node.optString("unit", fallback.unit.name))
        } catch (_: Exception) {
            fallback.unit
        }
        return RetentionPolicy(value, unit)
    }

    private fun parseTimestampMap(obj: JSONObject?, key: String): MutableMap<String, Long> {
        val result = mutableMapOf<String, Long>()
        val node = obj?.optJSONObject(key) ?: return result
        node.keys().forEach { itemKey ->
            val value = node.optLong(itemKey, 0L)
            if (value > 0L) {
                result[itemKey] = value
            }
        }
        return result
    }

    private fun parseUrlSettingsFromJson(json: String): UrlSettings? {
        return try {
            val obj = JSONObject(json)
            val defaults = defaultUrlSettings()
            val wifiArray = obj.optJSONArray("wifiRules") ?: JSONArray()
            val wifiRules = mutableListOf<WifiRule>()
            for (i in 0 until wifiArray.length()) {
                val item = wifiArray.optJSONObject(i) ?: continue
                val ssid = normalizeSsid(item.optString("ssid", ""))
                val url = item.optString("url", "")
                if (ssid.isNotBlank() && url.isNotBlank()) {
                    wifiRules.add(WifiRule(ssid, url))
                }
            }

            UrlSettings(
                defaultUrl = obj.optString("defaultUrl", ""),
                mobileUrl = obj.optString("mobileUrl", "").ifBlank { null },
                vpnUrl = obj.optString("vpnUrl", "").ifBlank { null },
                wifiRules = wifiRules,
                autoFallbackToDefault = obj.optBoolean("autoFallbackToDefault", true),
                defaultTimeoutSec = obj.optInt("defaultTimeoutSec", defaults.defaultTimeoutSec).coerceAtLeast(0),
                mobileTimeoutSec = obj.optInt("mobileTimeoutSec", defaults.mobileTimeoutSec).coerceAtLeast(0),
                vpnTimeoutSec = obj.optInt("vpnTimeoutSec", defaults.vpnTimeoutSec).coerceAtLeast(0),
                wifiTimeoutSec = obj.optInt("wifiTimeoutSec", defaults.wifiTimeoutSec).coerceAtLeast(0),
                cacheRetention = parseRetentionPolicy(obj, "cacheRetention", defaults.cacheRetention),
                localStorageRetention = parseRetentionPolicy(obj, "localStorageRetention", defaults.localStorageRetention),
                cookieRetention = parseRetentionPolicy(obj, "cookieRetention", defaults.cookieRetention),
                cacheRecords = parseTimestampMap(obj, "cacheRecords"),
                localStorageRecords = parseTimestampMap(obj, "localStorageRecords"),
                cookieRecords = parseTimestampMap(obj, "cookieRecords")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun retentionToJson(policy: RetentionPolicy): JSONObject {
        return JSONObject().apply {
            put("value", policy.value)
            put("unit", policy.unit.name)
        }
    }

    private fun mapToJson(data: Map<String, Long>): JSONObject {
        val json = JSONObject()
        data.forEach { (key, value) ->
            json.put(key, value)
        }
        return json
    }

    private fun settingsToJson(settings: UrlSettings): String {
        val wifiArray = JSONArray()
        settings.wifiRules.forEach {
            wifiArray.put(
                JSONObject().apply {
                    put("ssid", it.ssid)
                    put("url", it.url)
                }
            )
        }

        return JSONObject().apply {
            put("defaultUrl", settings.defaultUrl)
            put("mobileUrl", settings.mobileUrl ?: "")
            put("vpnUrl", settings.vpnUrl ?: "")
            put("wifiRules", wifiArray)
            put("autoFallbackToDefault", settings.autoFallbackToDefault)
            put("defaultTimeoutSec", settings.defaultTimeoutSec)
            put("mobileTimeoutSec", settings.mobileTimeoutSec)
            put("vpnTimeoutSec", settings.vpnTimeoutSec)
            put("wifiTimeoutSec", settings.wifiTimeoutSec)
            put("cacheRetention", retentionToJson(settings.cacheRetention))
            put("localStorageRetention", retentionToJson(settings.localStorageRetention))
            put("cookieRetention", retentionToJson(settings.cookieRetention))
            put("cacheRecords", mapToJson(settings.cacheRecords))
            put("localStorageRecords", mapToJson(settings.localStorageRecords))
            put("cookieRecords", mapToJson(settings.cookieRecords))
        }.toString(2)
    }

    private fun loadUrlSettings(): UrlSettings {
        val settingsFile = getSettingsFile()
        if (settingsFile != null && settingsFile.exists()) {
            val fromFile = parseUrlSettingsFromJson(settingsFile.readText(Charsets.UTF_8))
            if (fromFile != null) {
                return fromFile
            }
        }

        return defaultUrlSettings()
    }

    private fun saveUrlSettings(settings: UrlSettings) {
        val json = settingsToJson(settings)

        val settingsFile = getSettingsFile()
        if (settingsFile != null) {
            settingsFile.writeText(json, Charsets.UTF_8)
        }
    }

    private fun resolveAndLoadByNetwork(forceReload: Boolean = false) {
        val settings = loadUrlSettings()
        enforceRetentionPolicies(settings)
        if (settings.defaultUrl.isBlank()) {
            showUrlSettingsDialog(forceRequired = true)
            return
        }

        val target = resolveTarget(settings)
        if (!forceReload && currentResolvedTarget?.url == target.url) {
            return
        }

        currentResolvedTarget = target
        hasAttemptedFallback = false
        loadUrlWithIsolatedSiteData(target.url)
    }

    private fun resolveTarget(settings: UrlSettings): ResolvedTarget {
        val networkState = NetworkUtils.getNetworkState(this)

        if (networkState.isVpn && !settings.vpnUrl.isNullOrBlank()) {
            return ResolvedTarget(settings.vpnUrl, UrlSource.VPN)
        }

        if (networkState.type == NetworkUtils.NetworkType.WIFI) {
            val currentSsid = normalizeSsid(networkState.wifiSsid.orEmpty())
            if (currentSsid.isNotBlank()) {
                val matched = settings.wifiRules.firstOrNull {
                    it.ssid.equals(currentSsid, ignoreCase = true)
                }
                if (matched != null) {
                    return ResolvedTarget(matched.url, UrlSource.WIFI, currentSsid)
                }
            }
        }

        if (networkState.type == NetworkUtils.NetworkType.MOBILE && !settings.mobileUrl.isNullOrBlank()) {
            return ResolvedTarget(settings.mobileUrl, UrlSource.MOBILE)
        }

        return ResolvedTarget(settings.defaultUrl, UrlSource.DEFAULT)
    }

    private fun loadUrlWithIsolatedSiteData(rawUrl: String) {
        val targetUrl = processUrl(rawUrl)
        val targetProfileUrl = normalizeProfileUrl(targetUrl)
        if (activeProfileUrl == targetProfileUrl) {
            loadWebPage(targetUrl)
            return
        }

        persistCookiesForUrl(activeProfileUrl)
        switchCookieProfile {
            restoreCookiesForUrl(targetProfileUrl)
            activeProfileUrl = targetProfileUrl
            getPrefs().edit().putString(activeProfileUrlKey, targetProfileUrl).apply()
            loadWebPage(targetUrl)
        }
    }

    private fun resolveTimeoutSeconds(settings: UrlSettings): Int {
        return when (currentResolvedTarget?.source) {
            UrlSource.MOBILE -> settings.mobileTimeoutSec
            UrlSource.VPN -> settings.vpnTimeoutSec
            UrlSource.WIFI -> settings.wifiTimeoutSec
            else -> settings.defaultTimeoutSec
        }.coerceAtLeast(0)
    }

    private fun cookieStoreKey(url: String): String {
        val encodedUrl = Base64.encodeToString(
            normalizeProfileUrl(url).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return "PROFILE_COOKIES_$encodedUrl"
    }

    private fun persistCookiesForUrl(url: String?) {
        if (url.isNullOrBlank()) {
            return
        }
        val profileUrl = normalizeProfileUrl(url)
        val cookies = CookieManager.getInstance().getCookie(profileUrl)
        getPrefs().edit().putString(cookieStoreKey(profileUrl), cookies ?: "").apply()

        val settings = loadUrlSettings()
        settings.cookieRecords[profileUrl] = System.currentTimeMillis()
        saveUrlSettings(settings)
    }

    private fun restoreCookiesForUrl(url: String) {
        val profileUrl = normalizeProfileUrl(url)
        val cookies = getPrefs().getString(cookieStoreKey(profileUrl), null).orEmpty()
        if (cookies.isBlank()) {
            return
        }

        val cookieManager = CookieManager.getInstance()
        cookies.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { cookie ->
            cookieManager.setCookie(profileUrl, cookie)
        }
        cookieManager.flush()
    }

    private fun switchCookieProfile(onDone: () -> Unit) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            onDone()
        }
    }

    private fun handleMainFrameLoadFailure(errorMessage: String) {
        val settings = loadUrlSettings()
        val currentTarget = currentResolvedTarget

        val canFallback = settings.autoFallbackToDefault &&
            !hasAttemptedFallback &&
            currentTarget != null &&
            currentTarget.source != UrlSource.DEFAULT &&
            currentTarget.source != UrlSource.FALLBACK_DEFAULT &&
            settings.defaultUrl.isNotBlank() &&
            currentTarget.url != settings.defaultUrl

        if (canFallback) {
            hasAttemptedFallback = true
            currentResolvedTarget = ResolvedTarget(settings.defaultUrl, UrlSource.FALLBACK_DEFAULT)
            Toast.makeText(this, "当前网络 URL 不可用，已回退默认 URL", Toast.LENGTH_SHORT).show()
            loadUrlWithIsolatedSiteData(settings.defaultUrl)
            return
        }

        showErrorDialog(errorMessage)
    }

    private fun loadWebPage(url: String) {
        val settings = loadUrlSettings()
        startPageLoadTimeout(resolveTimeoutSeconds(settings))
        webView.loadUrl(url)
    }

    private fun showErrorDialog(errorMessage: String) {
        if (isShowingErrorDialog) {
            return
        }
        isShowingErrorDialog = true

        val contextWrapper = ContextThemeWrapper(this, R.style.Theme_OpenWebUI_Dialog)

        AlertDialog.Builder(contextWrapper)
            .setTitle("连接失败")
            .setMessage("无法连接到服务器: $errorMessage\n\n请检查网络连接或服务器地址是否正确。")
            .setPositiveButton("重试") { dialog, _ ->
                currentResolvedTarget?.url?.let { loadUrlWithIsolatedSiteData(it) }
                isShowingErrorDialog = false
                dialog.dismiss()
            }
            .setNegativeButton("退出") { dialog, _ ->
                isShowingErrorDialog = false
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun syncThemeToWeb() {
        val isDarkMode = resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val script = if (isDarkMode) {
            "(function() { if(typeof window !== 'undefined' && typeof localStorage !== 'undefined') { document.documentElement.setAttribute('data-theme', 'dark'); document.documentElement.classList.add('dark'); localStorage.setItem('theme', 'dark'); } })();"
        } else {
            "(function() { if(typeof window !== 'undefined' && typeof localStorage !== 'undefined') { document.documentElement.setAttribute('data-theme', 'light'); document.documentElement.classList.remove('dark'); localStorage.setItem('theme', 'light'); } })();"
        }

        if (::webView.isInitialized) {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun injectAppSettingsEntry() {
        if (!::webView.isInitialized) {
            return
        }

        val script = """
            (function () {
              if (window.__openWebUiAndroidInjected) return;
              window.__openWebUiAndroidInjected = true;

              const LINK_ID = 'openwebui-android-settings-entry';

              const buildEntry = () => {
                const container = document.getElementById('settings-tabs-container');
                if (!container) return;
                if (document.getElementById(LINK_ID)) return;

                const entry = document.createElement('a');
                entry.id = LINK_ID;
                entry.href = 'openwebui-android://settings';
                entry.draggable = false;
                entry.className = 'px-0.5 md:px-2.5 py-1 min-w-fit rounded-xl flex-1 md:flex-none flex select-none text-left transition text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white';
                entry.innerHTML = '<div class="self-center mr-2">&#9881;</div><div class="self-center">App Settings</div>';

                container.appendChild(entry);
              };

              buildEntry();

              const observer = new MutationObserver(() => {
                buildEntry();
              });
              observer.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    override fun onResume() {
        super.onResume()
        syncThemeToWeb()
        if (::webView.isInitialized) {
            if (settingsDialog?.isShowing == true) {
                return
            }
            if (loadUrlSettings().defaultUrl.isBlank()) {
                return
            }
            ensureWebViewIsHealthy()
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
        handleNetworkChangeEvent()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        super.onStop()
    }

    private fun ensureWebViewIsHealthy() {
        if (webView.url.isNullOrBlank()) {
            resolveAndLoadByNetwork(forceReload = true)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) {
            return
        }
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) {
            return
        }
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        networkCallbackRegistered = false
    }

    private fun handleNetworkChangeEvent() {
        runOnUiThread {
            if (!::webView.isInitialized) {
                return@runOnUiThread
            }
            if (settingsDialog?.isShowing == true) {
                return@runOnUiThread
            }
            if (loadUrlSettings().defaultUrl.isBlank()) {
                return@runOnUiThread
            }
            resolveAndLoadByNetwork()
        }
    }

    private fun openFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        filePickerLauncher.launch(intent)
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                val uris = mutableListOf<Uri>()

                if (data.clipData != null) {
                    val clipData = data.clipData
                    for (i in 0 until clipData!!.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                } else if (data.data != null) {
                    uris.add(data.data!!)
                }

                filePathCallback?.onReceiveValue(uris.toTypedArray())
            } else {
                filePathCallback?.onReceiveValue(null)
            }
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val permissionForFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        var allGranted = true
        for (result in grantResults.values) {
            if (!result) {
                allGranted = false
                break
            }
        }
        if (allGranted) {
            openFileChooser()
        } else {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private val requestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        if (pendingPermissionRequest != null) {
            val grantedResources = mutableListOf<String>()
            for ((permission, isGranted) in grantResults) {
                if (isGranted) {
                    when (permission) {
                        Manifest.permission.CAMERA -> {
                            for (resource in pendingPermissionRequest!!.resources) {
                                if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                                    grantedResources.add(resource)
                                }
                            }
                        }

                        Manifest.permission.RECORD_AUDIO -> {
                            for (resource in pendingPermissionRequest!!.resources) {
                                if (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                                    grantedResources.add(resource)
                                }
                            }
                        }
                    }
                }
            }

            pendingPermissionRequest?.grant(grantedResources.toTypedArray())
            pendingPermissionRequest = null
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            val webViewState = Bundle()
            webView.saveState(webViewState)
            outState.putBundle(WEBVIEW_STATE_KEY, webViewState)
        }
    }

    companion object {
        private const val WEBVIEW_STATE_KEY = "WEBVIEW_STATE"
    }
}
