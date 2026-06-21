package com.gte619n.anvil

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.widget.FrameLayout
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anvil Android shell (hybrid, stage 1): a hardened WebView hosting the Anvil web client over
 * Tailscale. Native modules (ADB-wifi mDNS discovery, FCM push) layer on in later stages.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val adbWifi by lazy { AdbWifi(this) }
    private var webReady = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris ?: emptyArray())
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen() // brand icon until the web app is ready (no white flash)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { !webReady }

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT // HTTP cache + service worker
            }
            setBackgroundColor(0xFF2F2739.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host ?: return false
                    if (host == BASE_HOST) return false // keep our origin in the app
                    startActivity(Intent(Intent.ACTION_VIEW, request.url)) // external links → browser
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    webReady = true // dismiss the splash once the shell is loaded
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    return try {
                        fileChooser.launch(params.createIntent())
                        true
                    } catch (_: Exception) {
                        filePathCallback = null
                        false
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
        }
        // Root container painted the brand color; padding it by the system bars physically insets
        // the WebView child, so app content never sits under the status/nav bars. The strips show
        // the brand color, with light status-bar icons.
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF2F2739.toInt()) }
        root.addView(web)
        setContentView(root)
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        root.postDelayed({ webReady = true }, 5_000) // safety: never hang on the splash

        // Native bridge: the web app (Settings) posts {type:"adb.connect"} → we discover the
        // wireless-debugging endpoint and tell the daemon to `adb connect`, then reply.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val uri = Uri.parse(BuildConfig.ANVIL_BASE_URL)
            val origin = buildString {
                append(uri.scheme).append("://").append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
            }
            WebViewCompat.addWebMessageListener(web, "AnvilNative", setOf(origin)) { _, message, _, _, replyProxy ->
                handleBridge(message.data ?: "", replyProxy)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Push (FCM): notification channel, runtime permission, and register this device's token.
        Notifications.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Net.postJson(BuildConfig.ANVIL_BASE_URL, "/api/push/fcm/register", JSONObject().put("token", token))
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            val sessionId = intent?.getStringExtra("sessionId") // notification-tap deep link
            web.loadUrl(if (sessionId != null) sessionUrl(sessionId) else BuildConfig.ANVIL_BASE_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("sessionId")?.let { web.loadUrl(sessionUrl(it)) }
    }

    private fun sessionUrl(sessionId: String): String = "${BuildConfig.ANVIL_BASE_URL}#s/${Uri.encode(sessionId)}"

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun handleBridge(data: String, reply: JavaScriptReplyProxy) {
        val json = runCatching { JSONObject(data) }.getOrNull()
        when (json?.optString("type")) {
            "adb.connect" -> adbWifi.discover(
                AdbWifi.CONNECT,
                onResult = { lanHost, port ->
                    val host = tailscaleIp() ?: lanHost // prefer the tailnet IP (LAN subnets often differ)
                    postAdb("/api/adb/connect", JSONObject().put("host", host).put("port", port), reply, "connect", host, port)
                },
                onError = { msg -> replyBridge(reply, false, msg, "connect") },
            )
            "adb.pair" -> {
                val code = json.optString("code")
                adbWifi.discover(
                    AdbWifi.PAIRING,
                    onResult = { lanHost, port ->
                        val host = tailscaleIp() ?: lanHost
                        postAdb("/api/adb/pair", JSONObject().put("host", host).put("port", port).put("code", code), reply, "pair", host, port)
                    },
                    onError = { msg -> replyBridge(reply, false, msg, "pair") },
                )
            }
            else -> replyBridge(reply, false, "Unknown native command", "")
        }
    }

    /** This device's Tailscale IPv4 (CGNAT 100.64.0.0/10), if Tailscale is up — reachable from the
     *  Mac across subnets, unlike the phone's LAN IP. */
    private fun tailscaleIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { val b = it.address; (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127 }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun postAdb(path: String, body: JSONObject, reply: JavaScriptReplyProxy, stage: String, host: String, port: Int) {
        Thread {
            try {
                val base = BuildConfig.ANVIL_BASE_URL.trimEnd('/')
                val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val parsed = runCatching { JSONObject(resp) }.getOrNull()
                val out = parsed?.optString("output", resp) ?: resp
                val ok = code in 200..299 && (parsed?.optBoolean("ok") ?: false)
                val verb = if (stage == "pair") "Paired" else "Connected"
                replyBridge(reply, ok, if (ok) "$verb $host:$port — $out" else "$host:$port → $out", stage)
            } catch (e: Exception) {
                replyBridge(reply, false, "Couldn't reach the server: ${e.message}", stage)
            }
        }.start()
    }

    private fun replyBridge(reply: JavaScriptReplyProxy, ok: Boolean, message: String, stage: String) {
        val json = JSONObject().put("type", "adb.result").put("ok", ok).put("stage", stage).put("message", message).toString()
        runOnUiThread {
            runCatching { reply.postMessage(json) }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        val BASE_HOST: String? = Uri.parse(BuildConfig.ANVIL_BASE_URL).host
    }
}
