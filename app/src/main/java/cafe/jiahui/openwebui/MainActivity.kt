package cafe.jiahui.openwebui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cafe.jiahui.openwebui.utils.NetworkUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    private val activeProfileUrlKey = "ACTIVE_PROFILE_URL"
    private val prefsName = "OpenWebUIPrefs"
    private val settingsFileName = "url_settings.json"

    private var currentResolvedTarget: ResolvedTarget? = null
    private var hasAttemptedFallback = false
    private var activeProfileUrl: String? = null
    private var isShowingErrorDialog = false
    private var pendingWebViewState: Bundle? = null
    private var networkCallbackRegistered = false
    private var pageLoadTimeoutRunnable: Runnable? = null
    private var pageLoadTimedOut = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handleNetworkChangeEvent()
        override fun onLost(network: Network) = handleNetworkChangeEvent()
        override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = handleNetworkChangeEvent()
    }

    private val permissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    // ── Compose state for settings dialog ──
    private var showSettingsDialog by mutableStateOf(false)
    private var forceSettingsRequired = false

    // Dialog tab state
    private var settingsNav by mutableStateOf("main") // "main" | "connection" | "storage"

    // Connection page state
    private var defaultUrlText by mutableStateOf("")
    private var mobileUrlText by mutableStateOf("")
    private var vpnUrlText by mutableStateOf("")
    private var autoFallbackChecked by mutableStateOf(true)
    private var defaultTimeoutText by mutableStateOf("25")
    private var mobileTimeoutText by mutableStateOf("25")
    private var vpnTimeoutText by mutableStateOf("25")
    private var wifiTimeoutText by mutableStateOf("25")
    private var wifiRulesState by mutableStateOf<List<WifiRule>>(emptyList())

    // Storage page state
    private var cacheRetentionText by mutableStateOf("0")
    private var localStorageRetentionText by mutableStateOf("0")
    private var cookieRetentionText by mutableStateOf("0")
    // validation errors
    private var cacheRetentionError by mutableStateOf(false)
    private var localStorageRetentionError by mutableStateOf(false)
    private var cookieRetentionError by mutableStateOf(false)
    private var cacheStatsText by mutableStateOf("")
    private var localStorageStatsText by mutableStateOf("")
    private var cookieStatsText by mutableStateOf("")

    // WiFi rule edit dialog
    private var showWifiRuleDialog by mutableStateOf(false)
    private var editingWifiRuleIndex by mutableStateOf(-1) // -1 = add new
    private var wifiRuleSsidText by mutableStateOf("")
    private var wifiRuleUrlText by mutableStateOf("")

    // Delete confirm dialog
    private var showDeleteWifiConfirm by mutableStateOf(false)
    private var deleteWifiRuleIndex by mutableStateOf(-1)

    private var dialogWasShown = false

    private enum class UrlSource {
        DEFAULT, MOBILE, VPN, WIFI, FALLBACK_DEFAULT
    }

    private data class WifiRule(val ssid: String, val url: String)

    private enum class RetentionUnit {
        SECOND, MINUTE, HOUR, DAY, PERMANENT
    }

    private data class RetentionPolicy(val value: Int, val unit: RetentionUnit)

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

    private data class StorageCategoryStats(val sizeBytes: Long, val count: Int)

    private data class StorageStats(
        val cache: StorageCategoryStats,
        val localStorage: StorageCategoryStats,
        val cookies: StorageCategoryStats
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== onCreate started ===")

        val isDark = resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isDark) {
            window.setBackgroundDrawableResource(android.R.color.black)
        } else {
            window.setBackgroundDrawableResource(android.R.color.white)
        }

        enableEdgeToEdge()
        activeProfileUrl = getPrefs().getString(activeProfileUrlKey, null)
        pendingWebViewState = savedInstanceState?.getBundle(WEBVIEW_STATE_KEY)
        Log.d(TAG, "activeProfileUrl=$activeProfileUrl, hasSavedState=${pendingWebViewState != null}")

        setContent {
            Log.d(TAG, "setContent composing...")
            val context = LocalContext.current
            val darkMode = resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            val colorScheme = if (darkMode) {
                darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF),
                    onPrimary = androidx.compose.ui.graphics.Color(0xFF381E72),
                    primaryContainer = androidx.compose.ui.graphics.Color(0xFF4F378B),
                    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFEADDFF),
                    secondary = androidx.compose.ui.graphics.Color(0xFFCCC2DC),
                    onSecondary = androidx.compose.ui.graphics.Color(0xFF332D41),
                    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF4A4458),
                    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE8DEF8),
                    surface = androidx.compose.ui.graphics.Color(0xFF161616),
                    onSurface = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
                    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFCAC4D0),
                    background = androidx.compose.ui.graphics.Color(0xFF161616),
                    onBackground = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
                    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
                    onError = androidx.compose.ui.graphics.Color(0xFF690005),
                    outline = androidx.compose.ui.graphics.Color(0xFF938F99)
                )
            } else {
                lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
                    onPrimary = androidx.compose.ui.graphics.Color.White,
                    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEADDFF),
                    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF21005D),
                    secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
                    onSecondary = androidx.compose.ui.graphics.Color.White,
                    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE8DEF8),
                    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF1D192B),
                    surface = androidx.compose.ui.graphics.Color.White,
                    onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
                    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
                    background = androidx.compose.ui.graphics.Color.White,
                    onBackground = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
                    error = androidx.compose.ui.graphics.Color(0xFFB3261E),
                    onError = androidx.compose.ui.graphics.Color.White,
                    outline = androidx.compose.ui.graphics.Color(0xFF79747E)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                val view = LocalView.current
                val statusBarColor = colorScheme.background
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    window.statusBarColor = statusBarColor.toArgb()
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkMode
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            Log.d(TAG, "AndroidView factory called, creating WebView")
                            WebView(ctx).also { wv ->
                                wv.layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                webView = wv
                                setupWebView(wv)
                                Log.d(TAG, "WebView setup complete, url=${wv.url}, size=${wv.width}x${wv.height}")
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    )
                }

                if (showSettingsDialog) {
                    AppSettingsDialog()
                }

                if (showWifiRuleDialog) {
                    WifiRuleEditDialog()
                }

                if (showDeleteWifiConfirm) {
                    DeleteWifiConfirmDialog()
                }
            }
        }

        requestPermissions()
    }

    @Composable
    private fun AppSettingsDialog() {
        AlertDialog(
            onDismissRequest = {
                if (settingsNav == "main" && (!forceSettingsRequired || !webView?.url.isNullOrBlank())) {
                    showSettingsDialog = false
                } else if (settingsNav != "main") {
                    settingsNav = "main"
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (settingsNav != "main") {
                        IconButton(onClick = { settingsNav = "main" }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "不保存返回")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        when (settingsNav) {
                            "connection" -> "连接设置"
                            "storage" -> "存储设置"
                            else -> "App设置"
                        }
                    )
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    when (settingsNav) {
                        "main" -> {
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text("连接") },
                                supportingContent = { Text("URL、WiFi规则、超时", fontSize = 13.sp) },
                                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                                colors = androidx.compose.material3.ListItemDefaults.colors(),
                                modifier = Modifier.clickable { settingsNav = "connection" }
                            )
                            HorizontalDivider()
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text("存储") },
                                supportingContent = { Text("保存时长、清除数据", fontSize = 13.sp) },
                                leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                                colors = androidx.compose.material3.ListItemDefaults.colors(),
                                modifier = Modifier.clickable { settingsNav = "storage" }
                            )
                        }
                        "connection" -> ConnectionTabContent()
                        "storage" -> StorageTabContent()
                    }
                }
            },
            confirmButton = {
                if (settingsNav != "main") {
                    TextButton(onClick = { saveSettingsAndApply() }) { Text("保存") }
                } else {
                    TextButton(onClick = {
                        if (forceSettingsRequired && webView?.url.isNullOrBlank()) finish()
                        else showSettingsDialog = false
                    }) { Text("关闭") }
                }
            },
            dismissButton = {
                if (settingsNav != "main") {
                    TextButton(onClick = { settingsNav = "main" }) { Text("返回") }
                }
            }
        )
    }

    @Composable
    private fun ConnectionTabContent() {
        OutlinedTextField(
            value = defaultUrlText,
            onValueChange = { defaultUrlText = it },
            label = { Text("默认URL（必填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "移动数据/VPN/WiFi规则 URL 不可用时自动回退默认 URL",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = autoFallbackChecked, onCheckedChange = { autoFallbackChecked = it })
        }

        OutlinedTextField(
            value = mobileUrlText,
            onValueChange = { mobileUrlText = it },
            label = { Text("移动数据 URL（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vpnUrlText,
            onValueChange = { vpnUrlText = it },
            label = { Text("VPN URL（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("WiFi规则", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            IconButton(onClick = {
                editingWifiRuleIndex = -1
                wifiRuleSsidText = ""
                wifiRuleUrlText = ""
                showWifiRuleDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }

        if (wifiRulesState.isEmpty()) {
            Text("暂无WiFi规则", fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        } else {
            wifiRulesState.forEachIndexed { index, rule ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.ssid, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(rule.url, fontSize = 12.sp)
                    }
                    IconButton(onClick = {
                        editingWifiRuleIndex = index
                        wifiRuleSsidText = rule.ssid
                        wifiRuleUrlText = rule.url
                        showWifiRuleDialog = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = {
                        deleteWifiRuleIndex = index
                        showDeleteWifiConfirm = true
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("超时设置", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        OutlinedTextField(
            value = defaultTimeoutText,
            onValueChange = { defaultTimeoutText = it.filter { c -> c.isDigit() } },
            label = { Text("默认超时（秒）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = mobileTimeoutText,
            onValueChange = { mobileTimeoutText = it.filter { c -> c.isDigit() } },
            label = { Text("移动数据超时（秒）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vpnTimeoutText,
            onValueChange = { vpnTimeoutText = it.filter { c -> c.isDigit() } },
            label = { Text("VPN超时（秒）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = wifiTimeoutText,
            onValueChange = { wifiTimeoutText = it.filter { c -> c.isDigit() } },
            label = { Text("WiFi规则超时（秒）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Composable
    private fun StorageTabContent() {
        Text("保存时长", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text("0=永久，-1=不保存，整数值需带单位（s/m/h/d，如 30d 表示30天）",
            fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))

        RetentionTextField(
            label = "页面缓存",
            value = cacheRetentionText,
            isError = cacheRetentionError,
            onValueChange = {
                cacheRetentionText = it
                cacheRetentionError = false
            }
        )
        RetentionTextField(
            label = "LocalStorage",
            value = localStorageRetentionText,
            isError = localStorageRetentionError,
            onValueChange = {
                localStorageRetentionText = it
                localStorageRetentionError = false
            }
        )
        RetentionTextField(
            label = "Cookies",
            value = cookieRetentionText,
            isError = cookieRetentionError,
            onValueChange = {
                cookieRetentionText = it
                cookieRetentionError = false
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("清除", fontWeight = FontWeight.Medium, fontSize = 14.sp)

        ClearRow(
            label = "页面缓存",
            stats = cacheStatsText,
            buttonText = "清除页面缓存",
            onClear = {
                val s = loadUrlSettings()
                clearPageCacheData(s)
                refreshStorageStats()
                showToast("页面缓存已清除")
            }
        )
        ClearRow(
            label = "LocalStorage",
            stats = localStorageStatsText,
            buttonText = "清除LocalStorage",
            onClear = {
                val s = loadUrlSettings()
                clearLocalStorageData(s)
                refreshStorageStats()
                showToast("LocalStorage已清除")
            }
        )
        ClearRow(
            label = "Cookies/登录数据",
            stats = cookieStatsText,
            buttonText = "清除登录数据",
            onClear = {
                val s = loadUrlSettings()
                clearLoginData(s) {
                    refreshStorageStats()
                    showToast("登录数据已清除")
                }
            }
        )

        Button(
            onClick = {
                val s = loadUrlSettings()
                clearAllStorageData(s) {
                    refreshStorageStats()
                    showToast("缓存/LocalStorage/Cookies 已全部清除")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("一键清除三项")
        }
    }

    @Composable
    private fun RetentionTextField(
        label: String,
        value: String,
        isError: Boolean,
        onValueChange: (String) -> Unit
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("$label 保存时长") },
                supportingText = if (isError) {
                    { Text("无效格式。0=永久，-1=不保存，或数字+单位(s/m/h/d)") }
                } else null,
                isError = isError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun ClearRow(
        label: String,
        stats: String,
        buttonText: String,
        onClear: () -> Unit
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(stats, fontSize = 12.sp)
            Button(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
                Text(buttonText, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun WifiRuleEditDialog() {
        AlertDialog(
            onDismissRequest = { showWifiRuleDialog = false },
            title = { Text(if (editingWifiRuleIndex >= 0) "编辑WiFi规则" else "添加WiFi规则") },
            text = {
                Column {
                    OutlinedTextField(
                        value = wifiRuleSsidText,
                        onValueChange = { wifiRuleSsidText = it },
                        label = { Text("WiFi名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = wifiRuleUrlText,
                        onValueChange = { wifiRuleUrlText = it },
                        label = { Text("对应URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ssid = normalizeSsid(wifiRuleSsidText.trim())
                    val urlRaw = wifiRuleUrlText.trim()
                    if (ssid.isBlank()) { showToast("WiFi名称不能为空"); return@TextButton }
                    if (urlRaw.isBlank()) { showToast("URL不能为空"); return@TextButton }
                    val processedUrl = processUrl(urlRaw)
                    if (!validateUrl(processedUrl)) { showToast("URL无效"); return@TextButton }

                    val rule = WifiRule(ssid, processedUrl)
                    val list = wifiRulesState.toMutableList()
                    if (editingWifiRuleIndex >= 0) {
                        list[editingWifiRuleIndex] = rule
                    } else {
                        val exists = list.indexOfFirst { it.ssid.equals(ssid, ignoreCase = true) }
                        if (exists >= 0) list[exists] = rule else list.add(rule)
                    }
                    wifiRulesState = list
                    showWifiRuleDialog = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWifiRuleDialog = false }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun DeleteWifiConfirmDialog() {
        val rule = wifiRulesState.getOrNull(deleteWifiRuleIndex) ?: return
        AlertDialog(
            onDismissRequest = { showDeleteWifiConfirm = false },
            title = { Text("删除确认") },
            text = { Text("确认删除 WiFi 规则 ${rule.ssid} ?") },
            confirmButton = {
                TextButton(onClick = {
                    wifiRulesState = wifiRulesState.toMutableList().apply { removeAt(deleteWifiRuleIndex) }
                    showDeleteWifiConfirm = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWifiConfirm = false }) { Text("取消") }
            }
        )
    }

    private fun refreshStorageStats() {
        val s = loadUrlSettings()
        val stats = calculateStorageStats(s)
        cacheStatsText = formatStorageStatText(stats.cache)
        localStorageStatsText = formatStorageStatText(stats.localStorage)
        cookieStatsText = formatStorageStatText(stats.cookies)
    }

    private fun saveSettingsAndApply() {
        val defaultUrlInput = defaultUrlText.trim()
        if (defaultUrlInput.isBlank()) { showToast("默认URL不能为空"); return }
        val processedDefault = processUrl(defaultUrlInput)
        if (!validateUrl(processedDefault)) { showToast("默认URL无效"); return }

        val processedMobile = if (mobileUrlText.isBlank()) null else {
            val p = processUrl(mobileUrlText.trim())
            if (!validateUrl(p)) { showToast("移动数据 URL无效"); return }
            p
        }
        val processedVpn = if (vpnUrlText.isBlank()) null else {
            val p = processUrl(vpnUrlText.trim())
            if (!validateUrl(p)) { showToast("VPN URL无效"); return }
            p
        }

        val defTO = defaultTimeoutText.toIntOrNull() ?: 25
        val mobTO = mobileTimeoutText.toIntOrNull() ?: 25
        val vpnTO = vpnTimeoutText.toIntOrNull() ?: 25
        val wifiTO = wifiTimeoutText.toIntOrNull() ?: 25

        val cachePol = parseRetentionText(cacheRetentionText, "页面缓存")
        if (cachePol == null) { cacheRetentionError = true; return }
        val lsPol = parseRetentionText(localStorageRetentionText, "LocalStorage")
        if (lsPol == null) { localStorageRetentionError = true; return }
        val cookiePol = parseRetentionText(cookieRetentionText, "Cookies")
        if (cookiePol == null) { cookieRetentionError = true; return }

        val latest = loadUrlSettings()
        val newSettings = latest.copy(
            defaultUrl = processedDefault,
            mobileUrl = processedMobile,
            vpnUrl = processedVpn,
            wifiRules = wifiRulesState,
            autoFallbackToDefault = autoFallbackChecked,
            defaultTimeoutSec = defTO,
            mobileTimeoutSec = mobTO,
            vpnTimeoutSec = vpnTO,
            wifiTimeoutSec = wifiTO,
            cacheRetention = cachePol,
            localStorageRetention = lsPol,
            cookieRetention = cookiePol
        )
        saveUrlSettings(newSettings)
        enforceRetentionPolicies(newSettings)
        resolveAndLoadByNetwork(forceReload = true)
        showSettingsDialog = false
    }

    private fun retentionToInputText(p: RetentionPolicy): String {
        if (p.unit == RetentionUnit.PERMANENT) return "0"
        if (p.value == 0 && p.unit != RetentionUnit.PERMANENT) return "-1"
        val suffix = when (p.unit) {
            RetentionUnit.SECOND -> "s"
            RetentionUnit.MINUTE -> "m"
            RetentionUnit.HOUR -> "h"
            RetentionUnit.DAY -> "d"
            RetentionUnit.PERMANENT -> ""
        }
        return "${p.value}$suffix"
    }

    private fun parseRetentionText(text: String, label: String): RetentionPolicy? {
        val t = text.trim()
        if (t == "0") return RetentionPolicy(0, RetentionUnit.PERMANENT)
        if (t == "-1") return RetentionPolicy(0, RetentionUnit.SECOND)
        if (t.isEmpty()) return null
        val regex = Regex("""^(-?\d+)\s*([smhd])$""", RegexOption.IGNORE_CASE)
        val match = regex.matchEntire(t) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        if (num <= 0) return null
        val unit = when (match.groupValues[2].lowercase()) {
            "s" -> RetentionUnit.SECOND
            "m" -> RetentionUnit.MINUTE
            "h" -> RetentionUnit.HOUR
            "d" -> RetentionUnit.DAY
            else -> return null
        }
        return RetentionPolicy(num, unit)
    }

    // ── legacy helpers wrapped ──

    private fun openSettingsDialogInternal(forceRequired: Boolean = false) {
        Log.d(TAG, "openSettingsDialogInternal(forceRequired=$forceRequired)")
        settingsNav = "main"
        forceSettingsRequired = forceRequired
        val s = loadUrlSettings()
        defaultUrlText = s.defaultUrl
        mobileUrlText = s.mobileUrl ?: ""
        vpnUrlText = s.vpnUrl ?: ""
        autoFallbackChecked = s.autoFallbackToDefault
        defaultTimeoutText = s.defaultTimeoutSec.toString()
        mobileTimeoutText = s.mobileTimeoutSec.toString()
        vpnTimeoutText = s.vpnTimeoutSec.toString()
        wifiTimeoutText = s.wifiTimeoutSec.toString()
        wifiRulesState = s.wifiRules

        cacheRetentionText = retentionToInputText(s.cacheRetention)
        localStorageRetentionText = retentionToInputText(s.localStorageRetention)
        cookieRetentionText = retentionToInputText(s.cookieRetention)
        cacheRetentionError = false
        localStorageRetentionError = false
        cookieRetentionError = false

        refreshStorageStats()
        showSettingsDialog = true
        dialogWasShown = true
    }

    // ── WebView setup ──

    private fun setupWebView(wv: WebView) {
        Log.d(TAG, "setupWebView start")
        wv.settings.apply {
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

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        wv.webViewClient = object : WebViewClient() {
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
                if (uri.scheme == "openwebui-android" && uri.host == "settings") {
                    openSettingsDialogInternal()
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    cancelPageLoadTimeout()
                    handleMainFrameLoadFailure(error?.description?.toString() ?: "未知错误")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                    cancelPageLoadTimeout()
                    handleMainFrameLoadFailure("HTTP错误: ${errorResponse?.statusCode}")
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, fpCallback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val requiredPermissions = mutableListOf<String>()
                val acceptTypes = params.acceptTypes ?: arrayOf()
                var needCam = false; var needMic = false
                for (t in acceptTypes) {
                    if (t.contains("image") || t.contains("picture") || t.contains("photo")) needCam = true
                    if (t.contains("audio") || t.contains("sound") || t.contains("voice")) needMic = true
                }
                if (needCam && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    requiredPermissions.add(Manifest.permission.CAMERA)
                if (needMic && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requiredPermissions.add(Manifest.permission.RECORD_AUDIO)

                if (requiredPermissions.isNotEmpty()) {
                    filePathCallback = fpCallback
                    permissionForFilePickerLauncher.launch(requiredPermissions.toTypedArray())
                    return true
                }
                filePathCallback = fpCallback
                openFileChooser()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val reqPerms = request?.resources ?: arrayOf()
                pendingPermissionRequest = request
                val toRequest = mutableListOf<String>()
                for (r in reqPerms) {
                    when (r) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                                toRequest.add(Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                                toRequest.add(Manifest.permission.RECORD_AUDIO)
                    }
                }
                if (toRequest.isNotEmpty()) requestLauncher.launch(toRequest.toTypedArray())
                else request?.grant(reqPerms)
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d("WebView", "${msg?.message()} -- From line ${msg?.lineNumber()} of ${msg?.sourceId()}")
                return super.onConsoleMessage(msg)
            }
        }

        if (!restoreWebViewStateIfNeeded()) {
            Log.d(TAG, "No saved state to restore, calling checkAndLoadServerUrl")
            checkAndLoadServerUrl()
        } else {
            Log.d(TAG, "Restored WebView state from saved instance")
        }
    }

    // ── business logic (unchanged from previous version, abridged for brevity) ──

    private fun restoreWebViewStateIfNeeded(): Boolean {
        val wv = webView ?: return false
        val state = pendingWebViewState ?: return false
        pendingWebViewState = null
        val restored = wv.restoreState(state) ?: return false
        if (restored.size == 0) return false
        val restoredUrl = wv.url
        if (!restoredUrl.isNullOrBlank()) {
            val processed = processUrl(restoredUrl)
            activeProfileUrl = processed
            currentResolvedTarget = ResolvedTarget(processed, UrlSource.DEFAULT)
            getPrefs().edit().putString(activeProfileUrlKey, processed).apply()
        }
        return true
    }

    private fun checkAndLoadServerUrl() {
        val settings = loadUrlSettings()
        Log.d(TAG, "checkAndLoadServerUrl: defaultUrl='${settings.defaultUrl}'")
        if (settings.defaultUrl.isBlank()) {
            Log.d(TAG, "No defaultUrl, showing settings dialog (forceRequired)")
            openSettingsDialogInternal(forceRequired = true)
        } else {
            Log.d(TAG, "Resolving URL by network...")
            resolveAndLoadByNetwork(forceReload = true)
        }
    }

    private fun validateUrl(url: String) = try {
        val uri = java.net.URI(url)
        uri.scheme != null && uri.host != null
    } catch (_: Exception) { false }

    private fun processUrl(url: String) = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url

    private fun normalizeSsid(ssid: String) = ssid.removePrefix("\"").removeSuffix("\"").trim()

    private fun getPrefs() = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun getSettingsFile(): File? {
        val dir = getExternalFilesDir(null) ?: return null
        if (!dir.exists()) dir.mkdirs()
        return File(dir, settingsFileName)
    }

    private fun defaultUrlSettings() = UrlSettings(
        defaultUrl = "", mobileUrl = null, vpnUrl = null, wifiRules = emptyList(),
        autoFallbackToDefault = true, defaultTimeoutSec = 25, mobileTimeoutSec = 25,
        vpnTimeoutSec = 25, wifiTimeoutSec = 25,
        cacheRetention = RetentionPolicy(0, RetentionUnit.PERMANENT),
        localStorageRetention = RetentionPolicy(0, RetentionUnit.PERMANENT),
        cookieRetention = RetentionPolicy(0, RetentionUnit.PERMANENT),
        cacheRecords = mutableMapOf(), localStorageRecords = mutableMapOf(), cookieRecords = mutableMapOf()
    )

    private fun parseRetentionPolicy(obj: JSONObject?, key: String, fallback: RetentionPolicy): RetentionPolicy {
        val node = obj?.optJSONObject(key) ?: return fallback
        val v = node.optInt("value", fallback.value).coerceAtLeast(0)
        val u = try { RetentionUnit.valueOf(node.optString("unit", fallback.unit.name)) } catch (_: Exception) { fallback.unit }
        return RetentionPolicy(v, u)
    }

    private fun parseTimestampMap(obj: JSONObject?, key: String): MutableMap<String, Long> {
        val r = mutableMapOf<String, Long>()
        obj?.optJSONObject(key)?.also { it.keys().forEach { k -> val v = it.optLong(k, 0L); if (v > 0L) r[k] = v } }
        return r
    }

    private fun parseUrlSettingsFromJson(json: String): UrlSettings? = try {
        val obj = JSONObject(json)
        val def = defaultUrlSettings()
        val arr = obj.optJSONArray("wifiRules") ?: JSONArray()
        val rules = mutableListOf<WifiRule>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val ssid = normalizeSsid(item.optString("ssid", ""))
            val url = item.optString("url", "")
            if (ssid.isNotBlank() && url.isNotBlank()) rules.add(WifiRule(ssid, url))
        }
        UrlSettings(
            defaultUrl = obj.optString("defaultUrl", ""),
            mobileUrl = obj.optString("mobileUrl", "").ifBlank { null },
            vpnUrl = obj.optString("vpnUrl", "").ifBlank { null },
            wifiRules = rules,
            autoFallbackToDefault = obj.optBoolean("autoFallbackToDefault", true),
            defaultTimeoutSec = obj.optInt("defaultTimeoutSec", def.defaultTimeoutSec).coerceAtLeast(0),
            mobileTimeoutSec = obj.optInt("mobileTimeoutSec", def.mobileTimeoutSec).coerceAtLeast(0),
            vpnTimeoutSec = obj.optInt("vpnTimeoutSec", def.vpnTimeoutSec).coerceAtLeast(0),
            wifiTimeoutSec = obj.optInt("wifiTimeoutSec", def.wifiTimeoutSec).coerceAtLeast(0),
            cacheRetention = parseRetentionPolicy(obj, "cacheRetention", def.cacheRetention),
            localStorageRetention = parseRetentionPolicy(obj, "localStorageRetention", def.localStorageRetention),
            cookieRetention = parseRetentionPolicy(obj, "cookieRetention", def.cookieRetention),
            cacheRecords = parseTimestampMap(obj, "cacheRecords"),
            localStorageRecords = parseTimestampMap(obj, "localStorageRecords"),
            cookieRecords = parseTimestampMap(obj, "cookieRecords")
        )
    } catch (_: Exception) { null }

    private fun settingsToJson(s: UrlSettings): String {
        val arr = JSONArray()
        s.wifiRules.forEach { arr.put(JSONObject().apply { put("ssid", it.ssid); put("url", it.url) }) }
        return JSONObject().apply {
            put("defaultUrl", s.defaultUrl); put("mobileUrl", s.mobileUrl ?: ""); put("vpnUrl", s.vpnUrl ?: "")
            put("wifiRules", arr); put("autoFallbackToDefault", s.autoFallbackToDefault)
            put("defaultTimeoutSec", s.defaultTimeoutSec); put("mobileTimeoutSec", s.mobileTimeoutSec)
            put("vpnTimeoutSec", s.vpnTimeoutSec); put("wifiTimeoutSec", s.wifiTimeoutSec)
            put("cacheRetention", retentionToJson(s.cacheRetention))
            put("localStorageRetention", retentionToJson(s.localStorageRetention))
            put("cookieRetention", retentionToJson(s.cookieRetention))
            put("cacheRecords", mapToJson(s.cacheRecords))
            put("localStorageRecords", mapToJson(s.localStorageRecords))
            put("cookieRecords", mapToJson(s.cookieRecords))
        }.toString(2)
    }

    private fun retentionToJson(p: RetentionPolicy) = JSONObject().apply { put("value", p.value); put("unit", p.unit.name) }
    private fun mapToJson(data: Map<String, Long>) = JSONObject().apply { data.forEach { (k, v) -> put(k, v) } }

    private fun loadUrlSettings(): UrlSettings {
        val f = getSettingsFile()
        if (f?.exists() == true) {
            parseUrlSettingsFromJson(f.readText(Charsets.UTF_8))?.let { return it }
        }
        return defaultUrlSettings()
    }

    private fun saveUrlSettings(s: UrlSettings) {
        getSettingsFile()?.writeText(settingsToJson(s), Charsets.UTF_8)
    }

    private fun resolveAndLoadByNetwork(forceReload: Boolean = false) {
        val settings = loadUrlSettings()
        enforceRetentionPolicies(settings)
        if (settings.defaultUrl.isBlank()) {
            openSettingsDialogInternal(forceRequired = true)
            return
        }
        val target = resolveTarget(settings)
        if (!forceReload && currentResolvedTarget?.url == target.url) return
        currentResolvedTarget = target
        hasAttemptedFallback = false
        loadUrlWithIsolatedSiteData(target.url)
    }

    private fun resolveTarget(settings: UrlSettings): ResolvedTarget {
        val ns = NetworkUtils.getNetworkState(this)
        if (ns.isVpn && !settings.vpnUrl.isNullOrBlank()) return ResolvedTarget(settings.vpnUrl, UrlSource.VPN)
        if (ns.type == NetworkUtils.NetworkType.WIFI) {
            val ssid = normalizeSsid(ns.wifiSsid.orEmpty())
            if (ssid.isNotBlank()) {
                settings.wifiRules.firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }?.let {
                    return ResolvedTarget(it.url, UrlSource.WIFI, ssid)
                }
            }
        }
        if (ns.type == NetworkUtils.NetworkType.MOBILE && !settings.mobileUrl.isNullOrBlank())
            return ResolvedTarget(settings.mobileUrl, UrlSource.MOBILE)
        return ResolvedTarget(settings.defaultUrl, UrlSource.DEFAULT)
    }

    private fun loadUrlWithIsolatedSiteData(rawUrl: String) {
        val targetUrl = processUrl(rawUrl)
        val targetProfile = normalizeProfileUrl(targetUrl)
        if (activeProfileUrl == targetProfile) { loadWebPage(targetUrl); return }
        persistCookiesForUrl(activeProfileUrl)
        switchCookieProfile {
            restoreCookiesForUrl(targetProfile)
            activeProfileUrl = targetProfile
            getPrefs().edit().putString(activeProfileUrlKey, targetProfile).apply()
            loadWebPage(targetUrl)
        }
    }

    private fun cookieStoreKey(url: String) = "PROFILE_COOKIES_${Base64.encodeToString(normalizeProfileUrl(url).toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)}"

    private fun persistCookiesForUrl(url: String?) {
        if (url.isNullOrBlank()) return
        val pu = normalizeProfileUrl(url)
        val cookies = CookieManager.getInstance().getCookie(pu)
        getPrefs().edit().putString(cookieStoreKey(pu), cookies ?: "").apply()
        val s = loadUrlSettings(); s.cookieRecords[pu] = System.currentTimeMillis(); saveUrlSettings(s)
    }

    private fun restoreCookiesForUrl(url: String) {
        val pu = normalizeProfileUrl(url)
        val cookies = getPrefs().getString(cookieStoreKey(pu), null).orEmpty()
        if (cookies.isBlank()) return
        val cm = CookieManager.getInstance()
        cookies.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { cm.setCookie(pu, it) }
        cm.flush()
    }

    private fun switchCookieProfile(onDone: () -> Unit) {
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); onDone() }
    }

    private fun handleMainFrameLoadFailure(errorMessage: String) {
        val settings = loadUrlSettings(); val ct = currentResolvedTarget
        if (settings.autoFallbackToDefault && !hasAttemptedFallback && ct != null &&
            ct.source != UrlSource.DEFAULT && ct.source != UrlSource.FALLBACK_DEFAULT &&
            settings.defaultUrl.isNotBlank() && ct.url != settings.defaultUrl) {
            hasAttemptedFallback = true
            currentResolvedTarget = ResolvedTarget(settings.defaultUrl, UrlSource.FALLBACK_DEFAULT)
            showToast("当前网络 URL 不可用，已回退默认 URL")
            loadUrlWithIsolatedSiteData(settings.defaultUrl)
            return
        }
        showErrorDialog(errorMessage)
    }

    private fun loadWebPage(url: String) {
        Log.d(TAG, "loadWebPage: $url")
        val s = loadUrlSettings()
        startPageLoadTimeout(when (currentResolvedTarget?.source) {
            UrlSource.MOBILE -> s.mobileTimeoutSec; UrlSource.VPN -> s.vpnTimeoutSec
            UrlSource.WIFI -> s.wifiTimeoutSec; else -> s.defaultTimeoutSec
        }.coerceAtLeast(0))
        webView?.loadUrl(url)
    }

    private fun showErrorDialog(errorMessage: String) {
        if (isShowingErrorDialog) return
        isShowingErrorDialog = true
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("连接失败")
            .setMessage("无法连接到服务器: $errorMessage\n\n请检查网络连接或服务器地址是否正确。")
            .setPositiveButton("重试") { d, _ -> currentResolvedTarget?.url?.let { loadUrlWithIsolatedSiteData(it) }; isShowingErrorDialog = false; d.dismiss() }
            .setNegativeButton("退出") { d, _ -> isShowingErrorDialog = false; d.dismiss(); finish() }
            .setCancelable(false)
        builder.show()
    }

    private fun syncThemeToWeb() {
        val isDark = resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val script = if (isDark) {
            "(function() { if(typeof window !== 'undefined' && typeof localStorage !== 'undefined') { document.documentElement.setAttribute('data-theme', 'dark'); document.documentElement.classList.add('dark'); localStorage.setItem('theme', 'dark'); } })();"
        } else {
            "(function() { if(typeof window !== 'undefined' && typeof localStorage !== 'undefined') { document.documentElement.setAttribute('data-theme', 'light'); document.documentElement.classList.remove('dark'); localStorage.setItem('theme', 'light'); } })();"
        }
        webView?.evaluateJavascript(script, null)
    }

    private fun injectAppSettingsEntry() {
        webView?.evaluateJavascript("""
            (function () {
              if (window.__openWebUiAndroidInjected) return;
              window.__openWebUiAndroidInjected = true;
              const LINK_ID = 'openwebui-android-settings-entry';
              const buildEntry = () => {
                if (document.getElementById(LINK_ID)) return;
                const tabGeneral = document.getElementById('tab-general');
                if (!tabGeneral) return;
                const hr = tabGeneral.querySelector('hr');
                if (!hr) return;
                const entry = document.createElement('div');
                entry.id = LINK_ID;
                entry.className = 'py-0.5 flex w-full justify-between';
                entry.innerHTML = '<div class="self-center text-xs font-medium">App\u8BBE\u7F6E</div>' +
                  '<button class="p-1 px-3 text-xs flex rounded-sm transition" type="button">' +
                  '<span class="ml-2 self-center">\u6253\u5F00</span></button>';
                entry.querySelector('button').addEventListener('click', function(e) {
                  e.preventDefault(); e.stopPropagation();
                  window.location = 'openwebui-android://settings';
                });
                const target = document.querySelector('#tab-general > div');
                if (target) target.appendChild(entry);
              };
              buildEntry();
              new MutationObserver(() => buildEntry()).observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent(), null)
    }

    private fun showToast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    // ── lifecycle ──

    override fun onResume() {
        super.onResume()
        syncThemeToWeb()
        if (webView != null && !showSettingsDialog && loadUrlSettings().defaultUrl.isNotBlank()) {
            ensureWebViewIsHealthy()
        }
    }

    override fun onStart() { super.onStart(); registerNetworkCallback(); handleNetworkChangeEvent() }
    override fun onStop() { unregisterNetworkCallback(); super.onStop() }

    private fun ensureWebViewIsHealthy() {
        if (webView?.url.isNullOrBlank()) resolveAndLoadByNetwork(forceReload = true)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.apply {
            try { registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback); networkCallbackRegistered = true } catch (_: Exception) {}
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.apply {
            try { unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        }
        networkCallbackRegistered = false
    }

    private fun handleNetworkChangeEvent() {
        runOnUiThread {
            if (webView == null || showSettingsDialog || loadUrlSettings().defaultUrl.isBlank()) return@runOnUiThread
            resolveAndLoadByNetwork()
        }
    }

    override fun onDestroy() { unregisterNetworkCallback(); webView?.destroy(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { val b = Bundle(); it.saveState(b); outState.putBundle(WEBVIEW_STATE_KEY, b) }
    }

    // ── file picker ──

    private fun openFileChooser() {
        filePickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) })
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val d = result.data
            if (d != null) {
                val uris = mutableListOf<Uri>()
                if (d.clipData != null) for (i in 0 until d.clipData!!.itemCount) uris.add(d.clipData!!.getItemAt(i).uri)
                else d.data?.let { uris.add(it) }
                filePathCallback?.onReceiveValue(uris.toTypedArray())
            } else filePathCallback?.onReceiveValue(null)
        } else filePathCallback?.onReceiveValue(null)
        filePathCallback = null
    }

    private val permissionForFilePickerLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        filePathCallback?.let {
            if (grants.values.all { it }) openFileChooser() else { it.onReceiveValue(null); filePathCallback = null }
        }
    }

    private val requestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        pendingPermissionRequest?.also { req ->
            val granted = mutableListOf<String>()
            grants.forEach { (perm, ok) ->
                if (ok) when (perm) {
                    Manifest.permission.CAMERA -> req.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }.forEach { granted.add(it) }
                    Manifest.permission.RECORD_AUDIO -> req.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.forEach { granted.add(it) }
                }
            }
            req.grant(granted.toTypedArray())
        }
        pendingPermissionRequest = null
    }

    // ── permissions ──

    private fun requestPermissions() {
        Log.d(TAG, "requestPermissions called")
        val toReq = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        Log.d(TAG, "Permissions to request: ${toReq.size}")
        if (toReq.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                it.forEach { (p, ok) -> if (!ok && !shouldShowRequestPermissionRationale(p)) showPermissionDeniedDialog(p) }
                Log.d(TAG, "Permissions result received, initializing...")
                initializeAfterPermissions()
            }.launch(toReq.toTypedArray())
        } else {
            Log.d(TAG, "All permissions granted, initializing immediately")
            initializeAfterPermissions()
        }
    }

    private fun initializeAfterPermissions() {
        Log.d(TAG, "initializeAfterPermissions, webView=$webView")
        runOnUiThread {
            if (webView == null) {
                Log.w(TAG, "webView is null in initializeAfterPermissions, deferring")
                return@runOnUiThread
            }
            if (!restoreWebViewStateIfNeeded()) checkAndLoadServerUrl()
        }
    }

    private fun showPermissionDeniedDialog(p: String) {
        val desc = when (p) { Manifest.permission.CAMERA -> "相机"; Manifest.permission.RECORD_AUDIO -> "麦克风"; Manifest.permission.ACCESS_FINE_LOCATION -> "位置"; else -> "此" }
        android.app.AlertDialog.Builder(this)
            .setTitle("需要权限").setMessage("应用需要${desc}权限才能正常使用相应功能。请前往设置手动开启。")
            .setPositiveButton("去设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }) }
            .setNegativeButton("稍后再说", null).setCancelable(false).show()
    }

    // ── storage helpers ──

    private fun normalizeProfileUrl(url: String) = processUrl(url).trim().removeSuffix("/")

    private fun startPageLoadTimeout(timeoutSec: Int) {
        cancelPageLoadTimeout()
        if (timeoutSec <= 0) return
        pageLoadTimedOut = false
        val r = Runnable { pageLoadTimedOut = true; handleMainFrameLoadFailure("请求超时（${timeoutSec}秒）") }
        pageLoadTimeoutRunnable = r; uiHandler.postDelayed(r, timeoutSec * 1000L)
    }

    private fun cancelPageLoadTimeout() { pageLoadTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }; pageLoadTimeoutRunnable = null; pageLoadTimedOut = false }

    private fun markCurrentUrlDataRecords(url: String?) {
        if (url.isNullOrBlank()) return
        val pu = normalizeProfileUrl(url); val now = System.currentTimeMillis()
        val s = loadUrlSettings(); s.cacheRecords[pu] = now; s.localStorageRecords[webOrigin(url)] = now; saveUrlSettings(s)
    }

    private fun webOrigin(url: String): String = try {
        val u = java.net.URI(processUrl(url))
        val scheme = u.scheme ?: "https"; val host = u.host ?: return normalizeProfileUrl(url)
        val dp = if (scheme.lowercase() == "http") 80 else if (scheme.lowercase() == "https") 443 else -1
        val port = if (u.port != -1 && u.port != dp) ":${u.port}" else ""
        "$scheme://$host$port"
    } catch (_: Exception) { normalizeProfileUrl(url) }

    private fun retentionUnitToIndex(u: RetentionUnit) = when (u) { RetentionUnit.SECOND -> 0; RetentionUnit.MINUTE -> 1; RetentionUnit.HOUR -> 2; RetentionUnit.DAY -> 3; RetentionUnit.PERMANENT -> 4 }

    private fun retentionMillis(p: RetentionPolicy): Long? = when (p.unit) {
        RetentionUnit.PERMANENT -> null; RetentionUnit.SECOND -> if (p.value <= 0) 0L else p.value * 1000L
        RetentionUnit.MINUTE -> if (p.value <= 0) 0L else p.value * 60_000L
        RetentionUnit.HOUR -> if (p.value <= 0) 0L else p.value * 3_600_000L
        RetentionUnit.DAY -> if (p.value <= 0) 0L else p.value * 86_400_000L
    }

    private fun pruneExpiredRecords(records: MutableMap<String, Long>, policy: RetentionPolicy, now: Long): List<String> {
        val ttl = retentionMillis(policy) ?: return emptyList()
        if (ttl == 0L) { val all = records.keys.toList(); records.clear(); return all }
        val expired = records.filter { now - it.value > ttl }.keys.toList(); expired.forEach { records.remove(it) }; return expired
    }

    private fun enforceRetentionPolicies(s: UrlSettings) {
        val now = System.currentTimeMillis(); var changed = false
        if (pruneExpiredRecords(s.cacheRecords, s.cacheRetention, now).isNotEmpty()) { webView?.clearCache(true); changed = true }
        val lsExpired = pruneExpiredRecords(s.localStorageRecords, s.localStorageRetention, now)
        if (lsExpired.isNotEmpty()) { lsExpired.forEach { try { WebStorage.getInstance().deleteOrigin(it) } catch (_: Exception) {} }; changed = true }
        val ckExpired = pruneExpiredRecords(s.cookieRecords, s.cookieRetention, now)
        if (ckExpired.isNotEmpty()) {
            val keys = ckExpired.map { cookieStoreKey(it) }; val e = getPrefs().edit(); keys.forEach { e.remove(it) }; e.apply()
            if (retentionMillis(s.cookieRetention) == 0L) { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
            changed = true
        }
        if (changed) saveUrlSettings(s)
    }

    private fun calculateDirectorySize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { calculateDirectorySize(it) } ?: 0L
    }

    private fun calculateStorageStats(settings: UrlSettings): StorageStats {
        val ad = File(applicationInfo.dataDir); val wvd = File(ad, "app_webview/Default")
        val cache = calculateDirectorySize(File(wvd, "Cache")) + calculateDirectorySize(cacheDir)
        val ls = calculateDirectorySize(File(wvd, "Local Storage")) + calculateDirectorySize(File(wvd, "IndexedDB"))
        val ck = getPrefs().all.filterKeys { it.startsWith("PROFILE_COOKIES_") }.values.mapNotNull { it as? String }.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        return StorageStats(StorageCategoryStats(cache, settings.cacheRecords.size), StorageCategoryStats(ls, settings.localStorageRecords.size), StorageCategoryStats(ck, settings.cookieRecords.size))
    }

    private fun formatStorageStatText(s: StorageCategoryStats): String {
        val mb = s.sizeBytes.toDouble() / 1024.0 / 1024.0
        return String.format(Locale.getDefault(), "大小: %.2f M | URL数量: %d", mb, s.count)
    }

    private fun clearPageCacheData(s: UrlSettings) { webView?.clearCache(true); s.cacheRecords.clear(); saveUrlSettings(s) }
    private fun clearLocalStorageData(s: UrlSettings) { WebStorage.getInstance().deleteAllData(); s.localStorageRecords.clear(); saveUrlSettings(s) }
    private fun clearLoginData(s: UrlSettings, onDone: () -> Unit) {
        val e = getPrefs().edit(); getPrefs().all.filterKeys { it.startsWith("PROFILE_COOKIES_") }.keys.forEach { e.remove(it) }; e.apply()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); s.cookieRecords.clear(); saveUrlSettings(s); onDone() }
    }
    private fun clearAllStorageData(s: UrlSettings, onDone: () -> Unit) { clearPageCacheData(s); clearLocalStorageData(s); clearLoginData(s, onDone) }

    companion object { private const val TAG = "OpenWebUI"; private const val WEBVIEW_STATE_KEY = "WEBVIEW_STATE" }
}
