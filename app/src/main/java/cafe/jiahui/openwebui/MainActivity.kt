package cafe.jiahui.openwebui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import cafe.jiahui.openwebui.utils.NetworkUtils
import android.content.res.Configuration
import android.view.View
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val SERVER_URL_KEY = "SERVER_URL"
    private val REQUEST_CODE_PERMISSIONS = 100

    // 权限列表
    private val permissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // 用于文件选择的回调
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    
    // 用于处理权限请求的回调
    private var permissionRequestCallback: PermissionRequest? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置状态栏颜色为纯黑或纯白
        setStatusBarForTheme()
        
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)

        // 请求必要权限
        requestPermissions()

        // 检查并加载服务器URL
        checkAndLoadServerUrl()
    }

    private fun setStatusBarForTheme() {
        // 获取当前系统的深色模式状态
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val window = this.window
            val decorView = window.decorView
            
            if (isDarkMode) {
                // 深色模式：使用OpenWebUI的暗色主题背景色 #171717
                window.statusBarColor = Color.parseColor("#171717")
                // 不设置SYSTEM_UI_FLAG_LIGHT_STATUS_BAR，因为深色背景下图标应为浅色
                decorView.systemUiVisibility = 0
            } else {
                // 浅色模式：纯白色状态栏 + 深色图标
                window.statusBarColor = getColor(android.R.color.white)
                // 设置SYSTEM_UI_FLAG_LIGHT_STATUS_BAR，使状态栏图标变为深色（黑色）
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        } else {
            // 对于较老版本的Android，统一设置为黑色状态栏
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
            // 使用registerForActivityResult注册权限请求
            val launcher = registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grantResults ->
                var allGranted = true
                var deniedPermissions = mutableListOf<String>()
                for ((permission, result) in grantResults) {
                    if (!result) {
                        allGranted = false
                        deniedPermissions.add(permission)
                        // 检查是否是必须的权限被永久拒绝
                        if (!shouldShowRequestPermissionRationale(permission)) {
                            // 权限被永久拒绝，提醒用户去设置页面开启
                            showPermissionDeniedDialog(permission)
                        }
                    }
                }
                if (allGranted) {
                    initializeWebView()
                } else {
                    Toast.makeText(this, "部分权限被拒绝，可能影响某些功能使用", Toast.LENGTH_LONG).show()
                    // 即使权限被拒绝也继续初始化WebView，只是某些功能可能受限
                    initializeWebView()
                }
            }
            launcher.launch(permissionsToRequest.toTypedArray())
        } else {
            // 权限已全部授予
            initializeWebView()
        }
    }

    private fun showPermissionDeniedDialog(permission: String) {
        val permissionDesc = when (permission) {
            Manifest.permission.CAMERA -> "相机"
            Manifest.permission.RECORD_AUDIO -> "麦克风"
            Manifest.permission.ACCESS_FINE_LOCATION -> "位置"
            Manifest.permission.READ_EXTERNAL_STORAGE, 
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "存储"
            else -> "此"
        }
        
        val message = "应用需要" + permissionDesc + "权限才能正常使用相应功能。请前往设置手动开启。"
        
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
            .setNegativeButton("稍后再说") { _, _ ->
                // 用户可以选择稍后手动开启
            }
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
            
            // 启用媒体播放和捕获
            mediaPlaybackRequiresUserGesture = false  // 允许自动播放媒体
        }
        
        // 启用WebRTC和其他媒体功能
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        
        // 设置WebView支持现代Web API
        webView.settings.mediaPlaybackRequiresUserGesture = false

        // 设置WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 页面开始加载时的处理
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完成，可以同步主题等
                syncThemeToWeb()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    // 主页面加载失败，显示URL配置对话框
                    showErrorDialog(error?.description?.toString() ?: "未知错误")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && errorResponse?.statusCode != 200) {
                    // HTTP错误，显示URL配置对话框
                    showErrorDialog("HTTP错误: ${errorResponse?.statusCode}")
                }
            }
        }

        // 设置WebChromeClient用于处理文件选择、媒体捕获等
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // 检查并请求必要的运行时权限
                val requiredPermissions = mutableListOf<String>()
                
                // 根据文件选择器参数判断需要哪些权限
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
                    // 需要额外权限，暂时保存回调
                    this@MainActivity.filePathCallback = filePathCallback
                    permissionForFilePickerLauncher.launch(requiredPermissions.toTypedArray())
                    return true
                }
                
                // 没有额外权限要求，直接打开文件选择器
                this@MainActivity.filePathCallback = filePathCallback
                openFileChooser()
                return true
            }

            // 处理JavaScript的权限请求 (如摄像头、麦克风)
            override fun onPermissionRequest(request: PermissionRequest?) {
                val requestedPermissions = request?.resources ?: arrayOf()
                
                // 保存当前权限请求，供后续使用
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
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> {
                            // 受保护的媒体ID，通常不需要特殊权限
                        }
                    }
                }

                if (permissionsToRequest.isNotEmpty()) {
                    // 请求缺少的权限
                    requestLauncher.launch(permissionsToRequest.toTypedArray())
                } else {
                    // 所有权限都已授予，直接授予请求的资源
                    request?.grant(requestedPermissions)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebView", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun checkAndLoadServerUrl() {
        val sharedPreferences = getSharedPreferences("OpenWebUIPrefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPreferences.getString(SERVER_URL_KEY, null)

        if (savedUrl.isNullOrEmpty()) {
            // 首次启动，显示URL配置对话框
            showUrlInputDialog("")
        } else {
            // 检查网络连接后再尝试加载
            if (NetworkUtils.isNetworkAvailable(this)) {
                loadWebPage(savedUrl)
            } else {
                // 网络不可用时仍然尝试加载（让应用自己处理网络错误）
                loadWebPage(savedUrl)
            }
        }
    }

    private fun showUrlInputDialog(initialUrl: String = "") {
        val contextWrapper = ContextThemeWrapper(this, R.style.Theme_OpenWebUI_Dialog)
        val layout = LinearLayout(contextWrapper).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val textInputLayout = TextInputLayout(
            contextWrapper
        ).apply {
            hint = "输入Open WebUI连接地址"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val editText = TextInputEditText(contextWrapper).apply {
            // 不设置任何hint，使用textInputLayout的浮动标签功能
            setText(initialUrl)
        }

        textInputLayout.addView(editText)
        layout.addView(textInputLayout)

        AlertDialog.Builder(contextWrapper)
            .setTitle("配置OpenWebUI实例")
            .setView(layout)
            .setPositiveButton("连接") { dialog, _ ->
                val url = editText.text.toString().trim()
                if (validateUrl(url)) {
                    saveAndLoadUrl(url)
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "请输入有效的URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { dialog, _ ->
                if (!this::webView.isInitialized || webView.url == null) {
                    // 如果还没有加载任何页面，关闭应用
                    dialog.dismiss()
                    finish()
                } else {
                    // 如果已有webView，就不关闭应用
                    dialog.dismiss()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun validateUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            uri.scheme != null && uri.host != null
        } catch (e: Exception) {
            false
        }
    }

    private fun saveAndLoadUrl(url: String) {
        val processedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        val sharedPreferences = getSharedPreferences("OpenWebUIPrefs", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString(SERVER_URL_KEY, processedUrl)
            apply()
        }

        loadWebPage(processedUrl)
    }

    private fun loadWebPage(url: String) {
        webView.loadUrl(url)
    }

    private fun showErrorDialog(errorMessage: String) {
        val sharedPreferences = getSharedPreferences("OpenWebUIPrefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPreferences.getString(SERVER_URL_KEY, "")

        val contextWrapper = ContextThemeWrapper(this, R.style.Theme_OpenWebUI_Dialog)
        
        AlertDialog.Builder(contextWrapper)
            .setTitle("连接失败")
            .setMessage("无法连接到服务器: $errorMessage\n\n请检查网络连接或服务器地址是否正确。")
            .setPositiveButton("重试") { dialog, _ ->
                savedUrl?.let { loadWebPage(it) }
                dialog.dismiss()
            }
            .setNeutralButton("更改地址") { dialog, _ ->
                dialog.dismiss()
                showUrlInputDialog(savedUrl ?: "")
            }
            .setNegativeButton("退出") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun syncThemeToWeb() {
        // 获取系统主题并同步到网页
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
        // 当应用回到前台时，再次检查并同步主题
        syncThemeToWeb()
    }

    private fun openFileChooser() {
        // 打开文件选择器的实现
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
            // 权限已授予，继续打开文件选择器
            openFileChooser()
        } else {
            // 用户拒绝了权限，取消文件选择
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }
    
    // 用于WebChromeClient的权限请求
    private val requestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        if (pendingPermissionRequest != null) {
            val grantedResources = mutableListOf<String>()
            for ((permission, isGranted) in grantResults) {
                if (isGranted) {
                    // 根据权限映射到相应的资源类型
                    when (permission) {
                        Manifest.permission.CAMERA -> {
                            // 检查原来请求的是什么资源
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
            
            // 授予已批准的权限
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