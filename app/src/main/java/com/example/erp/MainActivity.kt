package com.example.erp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import android.webkit.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        // true while MainActivity is alive (created + past the internet check), even if
        // NoInternetActivity is currently on top of it. Lets NoInternetActivity's Retry
        // just return to this same instance (same page, same session) instead of always
        // creating a fresh MainActivity that reloads the site root.
        @Volatile
        var isAlive: Boolean = false
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var spinnerImage: ImageView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ✅ PULL-TO-REFRESH GUARD — true whenever JS tells us ANY scrollable area on the page
    // (outer page, a navbar drawer, a "Hostel" panel, or any future panel) is currently
    // scrolled down (scrollTop > 0). SwipeRefreshLayout queries this live via
    // setOnChildScrollUpCallback on every touch move, so there's no stale-flag race condition.
    private var canScrollUpJs = false

    // ✅ CAMERA & MIC PERMISSION REQUEST
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, "Camera/Mic permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    // ✅ CHECK INTERNET
    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        // require VALIDATED too, not just the INTERNET capability — right after the user
        // flips internet back on, INTERNET can report true for a moment before the
        // connection is actually usable, which was causing an immediate failed load
        // ("Webpage not available") right after hitting Retry
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ✅ SHOW SPINNER
    // ✅ SAFETY NET — some phones/networks never fire onPageFinished (slow WebView builds,
    // stuck redirects, flaky resources, or WebView bugs that fire onPageStarted repeatedly
    // for iframes/sub-resources on some vendor builds). This forces the spinner to hide after
    // a max wait, no matter what — and crucially, repeated showSpinner() calls do NOT restart
    // the clock, so the absolute worst case is always 12s even under a reload loop.
    private val spinnerTimeoutHandler = Handler(Looper.getMainLooper())
    private val spinnerTimeoutRunnable = Runnable { hideSpinner() }
    private var spinnerCurrentlyVisible = false
    private fun showSpinner() {
        loadingLayout.visibility = View.VISIBLE
        spinnerImage.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.spinner_rotate)
        )
        if (!spinnerCurrentlyVisible) {
            spinnerCurrentlyVisible = true
            spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
            spinnerTimeoutHandler.postDelayed(spinnerTimeoutRunnable, 12000) // 12s max, from first show
        }
    }

    // ✅ Holds credentials from the most recent form submit until we know whether login succeeded.
    // Nothing is written to disk until success is confirmed — this way a wrong/old password
    // typed by mistake never overwrites a previously-saved good one.
    private var pendingLoginType: LoginType? = null
    private var pendingEmail: String? = null
    private var pendingPassword: String? = null

    // NOTE: we intentionally do NOT watch connectivity passively while the app is open.
    // NoInternetActivity should only appear as a RESULT of something the user actually did
    // (tapped a link/button that failed to load, or pulled to refresh) — not the instant the
    // connection drops in the background while they're just reading something already on screen.

    // true when the WebView's main page load actually failed (native error page rendered
    // underneath our overlay) — set false on any successful onPageFinished
    private var lastLoadFailed = false
    // caps automatic reload attempts after a failed load — without this, a persistently
    // failing load (flaky connection, or a buggy WebView misreporting a sub-resource as
    // main-frame) reloads forever and the app never actually finishes loading
    private var autoRetryCount = 0
    private val MAX_AUTO_RETRIES = 2

    // ✅ HIDE SPINNER
    private fun hideSpinner() {
        loadingLayout.visibility = View.GONE
        spinnerImage.clearAnimation()
        spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
        spinnerCurrentlyVisible = false
        // also clear the SwipeRefreshLayout's own ring — previously only onPageFinished/error
        // paths did this, so the 12s safety timeout could leave it spinning forever if a
        // pull-to-refresh reload never fired onPageFinished
        if (swipeRefresh.isRefreshing) {
            swipeRefresh.isRefreshing = false
        }
    }

    // ✅ DECIDE WHICH LOGIN THIS URL BELONGS TO (admin "/login" vs student "/student/login")
    private fun loginTypeForUrl(url: String?): LoginType? {
        if (url == null) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val path = uri?.path ?: url
        return when {
            path.contains("/student/login") -> LoginType.STUDENT
            path.contains("/student/front/login") -> LoginType.STUDENT
            path.endsWith("/login") -> LoginType.ADMIN
            else -> null
        }
    }

    // ✅ SECURITY: on explicit logout, wipe the saved credentials for that login type — so on a
    // shared/borrowed device, logging out actually clears the auto-fill for the next person.
    private fun logoutTypeForUrl(url: String?): LoginType? {
        if (url == null) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val path = uri?.path ?: url
        return when {
            path.contains("/student/logout") -> LoginType.STUDENT
            path.endsWith("/logout") -> LoginType.ADMIN
            else -> null
        }
    }

    // ✅ AUTO-FILL SAVED EMAIL/PASSWORD ON LOGIN PAGE (admin/student kept separate)
    // Supports both "email" (admin form) and "identifier" (student form) field names.
    private fun injectAutoFill(view: WebView?, type: LoginType) {
        if (!CredentialStore.hasSavedCredentials(this, type)) return

        val savedEmail = CredentialStore.getEmail(this, type).replace("\\", "\\\\").replace("'", "\\'")
        val savedPassword = CredentialStore.getPassword(this, type).replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                var userField = document.querySelector('#email') || document.querySelector('#identifier') ||
                                 document.querySelector('input[name="email"]') || document.querySelector('input[name="identifier"]') ||
                                 document.querySelector('input[name="username"]');
                var passField = document.querySelector('#password') || document.querySelector('input[name="password"]');
                if (userField) userField.value = '$savedEmail';
                if (passField) passField.value = '$savedPassword';
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    // ✅ CAPTURE EMAIL/PASSWORD WHEN LOGIN FORM IS SUBMITTED (does NOT save yet — only stashes it;
    // it's actually persisted only after we confirm the login succeeded, see onPageFinished below).
    // This also means if the password was recently changed on the ERP side, the new correct
    // password the user types in to log in will automatically become the new saved one.
    private fun injectFormCapture(view: WebView?, type: LoginType) {
        val js = """
            (function() {
                var form = document.querySelector('form');
                if (form && !form.dataset.bridgeAttached) {
                    form.dataset.bridgeAttached = 'true';
                    form.addEventListener('submit', function() {
                        var userField = document.querySelector('#email') || document.querySelector('#identifier') ||
                                         document.querySelector('input[name="email"]') || document.querySelector('input[name="identifier"]') ||
                                         document.querySelector('input[name="username"]');
                        var passField = document.querySelector('#password') || document.querySelector('input[name="password"]');
                        if (userField && passField && userField.value && passField.value) {
                            AndroidBridge.onLoginSubmit('${type.name}', userField.value, passField.value);
                        }
                    });
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    // ✅ NESTED-SCROLL GUARD (v3 — scroll-event based, not touch-guess based) —
    // Listens for actual 'scroll' events anywhere in the document (they don't bubble, but
    // DO fire in the capture phase on ancestors, so a single listener on `document` with
    // capture=true catches scrolling inside the outer page, a navbar drawer, a "Hostel"
    // panel, or any future nested panel — no need to guess ahead of time which element is
    // "scrollable"). Whenever ANY of them report scrollTop > 0, we tell native
    // AndroidBridge.setCanScrollUp(true), which SwipeRefreshLayout checks live via
    // setOnChildScrollUpCallback before allowing a pull-to-refresh gesture to start.
    private fun injectNestedScrollGuard(view: WebView?) {
        val js = """
            (function() {
                if (window.__ptrGuardAttached) return;
                window.__ptrGuardAttached = true;

                function report(scrollTop) {
                    AndroidBridge.setCanScrollUp(scrollTop > 0);
                }

                // capture:true is required — 'scroll' events don't bubble, but they DO
                // fire on ancestors (including document) during the capture phase. This
                // means we hear about scrolling inside ANY nested scrollable element
                // (drawer, dropdown, hostel panel, future panels, etc.) automatically.
                document.addEventListener('scroll', function(e) {
                    var t = e.target;
                    var scrollTop;
                    if (t === document) {
                        scrollTop = (document.scrollingElement || document.documentElement).scrollTop;
                    } else {
                        scrollTop = t.scrollTop;
                    }
                    report(scrollTop);
                }, true);

                // also cover the outer window itself scrolling
                window.addEventListener('scroll', function() {
                    report(window.scrollY);
                }, true);
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingLayout = findViewById(R.id.loadingLayout)
        spinnerImage = findViewById(R.id.spinnerImage)

        // ✅ START SPINNER
        showSpinner()

        // ✅ REQUEST CAMERA & MIC PERMISSIONS ON START
        requestCameraAndMicPermissions()

        // ✅ CHECK INTERNET ON LAUNCH
        if (!isInternetAvailable()) {
            startActivity(Intent(this, NoInternetActivity::class.java))
            finish()
            return
        }
        isAlive = true

        // ✅ COOKIE PERSISTENCE
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // ✅ WEBVIEW SETTINGS
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            saveFormData = false
        }

        // ✅ SECURITY: stop Android's system Autofill framework and the WebView's own
        // "save password?" popup from touching this WebView. We manage credentials
        // ourselves (encrypted, per login type) — we don't want the OS/keyboard layer
        // caching or offering to sync them anywhere else.
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO

        // ✅ FIX SCROLLING
        webView.scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
        webView.overScrollMode = WebView.OVER_SCROLL_ALWAYS
        webView.isScrollbarFadingEnabled = true
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = true

        // ✅ PULL-TO-REFRESH — SwipeRefreshLayout stays enabled at all times, but before
        // starting a pull gesture it asks this callback "can the child still scroll up?".
        // We answer live from canScrollUpJs, which JS keeps updated via real scroll events
        // (see injectNestedScrollGuard). This replaces manually toggling `isEnabled`, which
        // had race conditions and only ever tracked the outer page, never nested panels.
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> canScrollUpJs }

        // ✅ NAVY BLUE SWIPE REFRESH COLOR
        swipeRefresh.setColorSchemeColors(android.graphics.Color.WHITE)
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            android.graphics.Color.parseColor("#001F5B")
        )

        // ✅ JS BRIDGE — STASHES email/password WHEN USER SUBMITS LOGIN FORM
        // (not saved yet — only persisted once login is confirmed successful, in onPageFinished below)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onLoginSubmit(typeName: String, email: String, password: String) {
                if (email.isNotBlank() && password.isNotBlank()) {
                    val type = try {
                        LoginType.valueOf(typeName)
                    } catch (e: Exception) {
                        null
                    }
                    if (type != null) {
                        runOnUiThread {
                            pendingLoginType = type
                            pendingEmail = email
                            pendingPassword = password
                        }
                    }
                }
            }

            // ✅ Called from injected JS on every real scroll event, anywhere in the page
            // (outer page, navbar drawer, hostel panel, any future panel). Tells native
            // whether pull-to-refresh should be allowed to start right now.
            @JavascriptInterface
            fun setCanScrollUp(canScrollUp: Boolean) {
                runOnUiThread {
                    canScrollUpJs = canScrollUp
                }
            }
        }, "AndroidBridge")

        // ✅ WEBVIEW CLIENT — FIXED SPINNER STUCK BUG
        webView.webViewClient = object : WebViewClient() {
            private var isMainFrameFinished = false
            // WebView still calls onPageFinished even after onReceivedError fires for the
            // SAME failed main-frame load (this is normal Chromium WebView behavior — the
            // "page" it finished loading is its own native error page). Without this flag,
            // onPageFinished's success-path would run anyway: it hides our spinner overlay
            // 300ms later, which can flash the native "Webpage not available" page for a
            // moment if NoInternetActivity's enter transition hasn't fully covered the
            // screen yet. onReceivedError already fully handles the failure (redirects to
            // NoInternetActivity or schedules a retry) — onPageFinished just needs to stand
            // down when this is set.
            private var currentMainFrameLoadFailed = false

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val destUrl = request?.url.toString()
                // ✅ Explicit logout → clear saved credentials for that login type (security)
                val logoutType = logoutTypeForUrl(destUrl)
                if (logoutType != null) {
                    CredentialStore.clear(this@MainActivity, logoutType)
                }
                view?.loadUrl(destUrl)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isMainFrameFinished = false
                currentMainFrameLoadFailed = false
                // fresh page load — reset scroll-up state so a stale "true" from the
                // previous page can't block pull-to-refresh on the new one
                canScrollUpJs = false
                showSpinner()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isMainFrameFinished) return  // ← ignore duplicate calls
                if (currentMainFrameLoadFailed) {
                    // this onPageFinished belongs to a load that onReceivedError already
                    // handled (NoInternetActivity launched, or a retry scheduled) — don't
                    // run the success path, and don't touch the spinner overlay here
                    return
                }
                isMainFrameFinished = true
                lastLoadFailed = false
                autoRetryCount = 0
                injectNestedScrollGuard(view)

                // Small delay so JS settles before hiding spinner
                webView.postDelayed({
                    hideSpinner()
                    swipeRefresh.isRefreshing = false
                    CookieManager.getInstance().flush()
                }, 300)

                // ✅ ONLY RUN ON LOGIN PAGE — and only for the matching login type
                val currentType = loginTypeForUrl(url)
                if (currentType != null) {
                    // If we land BACK on the same login page right after a pending submit for
                    // this same type, that means the login FAILED (wrong password) — discard it
                    // so a bad attempt never gets persisted later by mistake.
                    if (pendingLoginType == currentType) {
                        pendingLoginType = null
                        pendingEmail = null
                        pendingPassword = null
                    }

                    // Auto check "remember me" checkbox
                    view?.evaluateJavascript("""
                        (function() {
                            var cb = document.getElementById('remember');
                            if (cb && !cb.checked) { cb.checked = true; }
                        })();
                    """.trimIndent(), null)

                    // Auto-fill saved credentials (for THIS login type only) + capture new ones on submit
                    injectAutoFill(view, currentType)
                    injectFormCapture(view, currentType)
                } else {
                    // ✅ We've navigated AWAY from a login page — if a submit was pending, that
                    // means login SUCCEEDED, so now it's safe to persist it.
                    val pType = pendingLoginType
                    val pEmail = pendingEmail
                    val pPass = pendingPassword
                    if (pType != null && pEmail != null && pPass != null) {
                        CredentialStore.save(this@MainActivity, pType, pEmail, pPass)
                    }
                    pendingLoginType = null
                    pendingEmail = null
                    pendingPassword = null
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    currentMainFrameLoadFailed = true
                    lastLoadFailed = true
                    if (!isInternetAvailable()) {
                        // Check FIRST, before touching the spinner overlay — WebView already
                        // painted its own native "Webpage not available" page by this point,
                        // so hiding our overlay first would flash it. Go straight to
                        // NoInternetActivity (overlay stays up, covering it) and keep
                        // MainActivity alive underneath instead of finishing it.
                        startActivity(Intent(this@MainActivity, NoInternetActivity::class.java))
                        return
                    }
                    swipeRefresh.isRefreshing = false
                    // internet is available but this particular load still failed (can
                    // happen right after connectivity comes back, before it's fully settled)
                    // — keep the spinner overlay UP (don't hide it) so the browser's error
                    // page underneath is never visible, and retry automatically, up to a cap
                    if (autoRetryCount >= MAX_AUTO_RETRIES) {
                        // stop looping — this isn't a transient blip, bail out to the
                        // no-internet screen so the user has a manual way out instead of
                        // the app silently reloading forever
                        startActivity(Intent(this@MainActivity, NoInternetActivity::class.java))
                        return
                    }
                    autoRetryCount++
                    view?.postDelayed({
                        if (isInternetAvailable()) {
                            view.reload()
                        } else {
                            // still not actually connected — the safety-timeout in showSpinner
                            // already caps this, but bail to the no-internet screen now too
                            startActivity(Intent(this@MainActivity, NoInternetActivity::class.java))
                        }
                    }, 800)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    hideSpinner()
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        // ✅ CHROME CLIENT
        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select File"))
                return true
            }

            override fun onJsAlert(
                view: WebView?, url: String?,
                message: String?, result: JsResult?
            ): Boolean {
                result?.confirm()
                return false
            }
        }

        // ✅ DOWNLOAD SUPPORT
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                request.setDescription("Downloading file...")
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(fileName)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName
                )
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // ✅ LOAD WEBSITE (restore session if returning)
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://codemind25.com/")
        }

        // ✅ SWIPE TO REFRESH
        swipeRefresh.setOnRefreshListener {
            if (isInternetAvailable()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                startActivity(Intent(this, NoInternetActivity::class.java))
                // no finish() — the page already loaded fine, just keep it as-is underneath
            }
        }

        // ✅ BACK BUTTON WITH EXIT CONFIRMATION
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Exit MPU ERP")
                    .setMessage("Are you sure you want to exit?")
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .setNegativeButton("Stay") { dialog, _ -> dialog.dismiss() }
                    .setCancelable(false)
                    .show()
                    .also { dialog ->
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                            ?.setTextColor(android.graphics.Color.parseColor("#001F5B"))
                        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                            ?.setTextColor(android.graphics.Color.parseColor("#C9A84C"))
                    }
            }
        }

    } // ✅ END OF onCreate

    // ✅ REQUEST CAMERA & MIC AT RUNTIME
    private fun requestCameraAndMicPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // ✅ FILE PICKER RESULT
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results: Array<Uri>? = when {
                    data?.clipData != null -> {
                        val count = data.clipData!!.itemCount
                        Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
                filePathCallback?.onReceiveValue(results)
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

    // ✅ SAVE WEBVIEW STATE (fixes session on app reopen)
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // ✅ WHENEVER APP COMES TO FOREGROUND — recover from a failed load if the page underneath
    // is a stale error page, and keep the spinner safety timer accurate
    override fun onResume() {
        super.onResume()

        if (::webView.isInitialized) {
            if (lastLoadFailed) {
                // the page underneath was a failed/error load (e.g. we went to
                // NoInternetActivity mid-load) — reload it now instead of just resuming,
                // which would otherwise expose the stale native error page once the
                // overlay eventually hides
                if (isInternetAvailable()) {
                    lastLoadFailed = false  // if this reload also fails, onReceivedError's own
                    // retry cap takes over — this only fires once per
                    // pause/resume instead of looping here too
                    showSpinner()
                    webView.reload()
                }
                // if still no internet, onResume's own network state will route back to
                // NoInternetActivity via the connectivity checks already in place
            } else if (spinnerCurrentlyVisible) {
                // spinner was legitimately still showing when we got backgrounded — restart
                // its 12s safety countdown fresh now that we're in the foreground again,
                // instead of letting it have counted down invisibly while paused
                spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
                spinnerTimeoutHandler.postDelayed(spinnerTimeoutRunnable, 12000)
            }
        }
    }

    // ✅ SAVE COOKIES WHEN APP GOES TO BACKGROUND
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        // pause the spinner safety timer while backgrounded — otherwise it can fire while
        // NoInternetActivity is on top, hiding our overlay early and exposing whatever the
        // WebView rendered underneath (e.g. its native error page) once we return
        spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
    }

    // ✅ CLEANUP — cancel any pending spinner timeout / callbacks when activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        spinnerTimeoutHandler.removeCallbacks(spinnerTimeoutRunnable)
        isAlive = false
    }

} // ✅ END OF CLASS