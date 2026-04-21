package cafe.jiahui.openwebui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import cafe.jiahui.openwebui.utils.NetworkUtils
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        WIFI,
        FALLBACK_DEFAULT
    }

    private data class WifiRule(
        val ssid: String,
        val url: String
    )

    private data class UrlSettings(
        val defaultUrl: String,
        val mobileUrl: String?,
        val wifiRules: List<WifiRule>,
        val autoFallbackToDefault: Boolean
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
                syncThemeToWeb()
                isShowingErrorDialog = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
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

        checkAndLoadServerUrl()
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

        val defaultUrlLayout = TextInputLayout(contextWrapper).apply {
            hint = "默认 URL（必填）"
        }
        val defaultUrlEdit = TextInputEditText(contextWrapper).apply {
            setText(settings.defaultUrl)
        }
        defaultUrlLayout.addView(defaultUrlEdit)
        rootLayout.addView(defaultUrlLayout)

        val mobileUrlLayout = TextInputLayout(contextWrapper).apply {
            hint = "移动数据 URL（可选）"
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 24
            layoutParams = params
        }
        val mobileUrlEdit = TextInputEditText(contextWrapper).apply {
            setText(settings.mobileUrl ?: "")
        }
        mobileUrlLayout.addView(mobileUrlEdit)
        rootLayout.addView(mobileUrlLayout)

        val fallbackSwitch = SwitchMaterial(contextWrapper).apply {
            text = "移动数据/WiFi规则 URL 不可用时自动回退默认 URL"
            isChecked = settings.autoFallbackToDefault
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 24
            layoutParams = params
        }
        rootLayout.addView(fallbackSwitch)

        val wifiRulesContainer = LinearLayout(contextWrapper).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 24
            layoutParams = params
        }
        rootLayout.addView(wifiRulesContainer)

        val wifiRows = mutableListOf<Pair<TextInputEditText, TextInputEditText>>()

        fun addWifiRuleRow(ssid: String = "", url: String = "") {
            val rowLayout = LinearLayout(contextWrapper).apply {
                orientation = LinearLayout.VERTICAL
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 16
                layoutParams = params
            }

            val ssidLayout = TextInputLayout(contextWrapper).apply {
                hint = "WiFi SSID"
            }
            val ssidEdit = TextInputEditText(contextWrapper).apply {
                setText(ssid)
            }
            ssidLayout.addView(ssidEdit)

            val urlLayout = TextInputLayout(contextWrapper).apply {
                hint = "该 WiFi 使用的 URL"
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 12
                layoutParams = params
            }
            val urlEdit = TextInputEditText(contextWrapper).apply {
                setText(url)
            }
            urlLayout.addView(urlEdit)

            val removeBtn = Button(contextWrapper).apply {
                text = "删除此规则"
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = 8
                layoutParams = params
                setOnClickListener {
                    wifiRulesContainer.removeView(rowLayout)
                    wifiRows.removeIf { it.first == ssidEdit && it.second == urlEdit }
                }
            }

            rowLayout.addView(ssidLayout)
            rowLayout.addView(urlLayout)
            rowLayout.addView(removeBtn)
            wifiRulesContainer.addView(rowLayout)
            wifiRows.add(ssidEdit to urlEdit)
        }

        settings.wifiRules.forEach { addWifiRuleRow(it.ssid, it.url) }
        if (settings.wifiRules.isEmpty()) {
            addWifiRuleRow()
        }

        val addWifiBtn = Button(contextWrapper).apply {
            text = "新增 WiFi 规则"
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 16
            layoutParams = params
            setOnClickListener { addWifiRuleRow() }
        }
        rootLayout.addView(addWifiBtn)

        val scrollView = ScrollView(contextWrapper).apply {
            addView(rootLayout)
        }

        val dialog = AlertDialog.Builder(contextWrapper)
            .setTitle("连接策略设置")
            .setView(scrollView)
            .setPositiveButton("保存") { dialogInterface, _ ->
                val defaultUrlInput = defaultUrlEdit.text?.toString()?.trim().orEmpty()
                val mobileUrlInput = mobileUrlEdit.text?.toString()?.trim().orEmpty()

                if (defaultUrlInput.isBlank()) {
                    Toast.makeText(this, "默认 URL 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val processedDefault = processUrl(defaultUrlInput)
                if (!validateUrl(processedDefault)) {
                    Toast.makeText(this, "默认 URL 无效", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val processedMobile = if (mobileUrlInput.isBlank()) {
                    null
                } else {
                    val candidate = processUrl(mobileUrlInput)
                    if (!validateUrl(candidate)) {
                        Toast.makeText(this, "移动数据 URL 无效", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    candidate
                }

                val rules = mutableListOf<WifiRule>()
                for ((ssidEdit, urlEdit) in wifiRows) {
                    val ssid = normalizeSsid(ssidEdit.text?.toString()?.trim().orEmpty())
                    val url = urlEdit.text?.toString()?.trim().orEmpty()
                    if (ssid.isBlank() && url.isBlank()) {
                        continue
                    }
                    if (ssid.isBlank() || url.isBlank()) {
                        Toast.makeText(this, "WiFi 规则需同时填写 SSID 和 URL", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val processedRuleUrl = processUrl(url)
                    if (!validateUrl(processedRuleUrl)) {
                        Toast.makeText(this, "WiFi 规则 URL 无效：$ssid", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    rules.add(WifiRule(ssid = ssid, url = processedRuleUrl))
                }

                val newSettings = UrlSettings(
                    defaultUrl = processedDefault,
                    mobileUrl = processedMobile,
                    wifiRules = rules,
                    autoFallbackToDefault = fallbackSwitch.isChecked
                )

                saveUrlSettings(newSettings)
                resolveAndLoadByNetwork(forceReload = true)
                dialogInterface.dismiss()
            }
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

        dialog.setOnDismissListener {
            settingsDialog = null
        }
        settingsDialog = dialog
        dialog.show()
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

    private fun parseUrlSettingsFromJson(json: String): UrlSettings? {
        return try {
            val obj = JSONObject(json)
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
                wifiRules = wifiRules,
                autoFallbackToDefault = obj.optBoolean("autoFallbackToDefault", true)
            )
        } catch (_: Exception) {
            null
        }
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
            put("wifiRules", wifiArray)
            put("autoFallbackToDefault", settings.autoFallbackToDefault)
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

        return UrlSettings("", null, emptyList(), true)
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
        if (activeProfileUrl == targetUrl) {
            loadWebPage(targetUrl)
            return
        }

        persistCookiesForUrl(activeProfileUrl)
        clearSiteData {
            restoreCookiesForUrl(targetUrl)
            activeProfileUrl = targetUrl
            getPrefs().edit().putString(activeProfileUrlKey, targetUrl).apply()
            loadWebPage(targetUrl)
        }
    }

    private fun cookieStoreKey(url: String): String {
        return "PROFILE_COOKIES_${url.hashCode()}"
    }

    private fun persistCookiesForUrl(url: String?) {
        if (url.isNullOrBlank()) {
            return
        }
        val cookies = CookieManager.getInstance().getCookie(url)
        getPrefs().edit().putString(cookieStoreKey(url), cookies ?: "").apply()
    }

    private fun restoreCookiesForUrl(url: String) {
        val cookies = getPrefs().getString(cookieStoreKey(url), null).orEmpty()
        if (cookies.isBlank()) {
            return
        }

        val cookieManager = CookieManager.getInstance()
        cookies.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { cookie ->
            cookieManager.setCookie(url, cookie)
        }
        cookieManager.flush()
    }

    private fun clearSiteData(onDone: () -> Unit) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
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
        webView.destroy()
        super.onDestroy()
    }
}
