/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.split

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.data.prefs.SplitScreenPreferences
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.model.UserAgentProfile
import com.kododake.aabrowser.ui.compose.theme.AABrowserTheme
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.UserAgentManager
import com.kododake.aabrowser.web.configureWebView
import com.kododake.aabrowser.web.releaseCompletely
import kotlin.math.abs

/**
 * In-app split screen: keeps the regular browser on one side and a second, independent
 * WebView pinned to a web app (Slack by default) on the other side.
 *
 * Android Auto only offers its own split layout to navigation/media template apps, so a
 * full browser can never sit next to another app there. Instead we split our own window:
 * the result looks and behaves like "half AABrowser, half Slack".
 */
class SplitScreenController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    /** Opens a URL in the main browser (used for links the pinned app opens in a new window). */
    private val openInMainBrowser: (String) -> Unit,
    /** Notifies the host (e.g. menu switch state) when split screen is toggled. */
    private val onEnabledChanged: (Boolean) -> Unit
) {

    private val toolbarState = SplitPaneToolbarState()
    private var paneWebView: WebView? = null
    private var browserRatio: Float = SplitScreenPreferences.getRatio(activity)
    private var paneOnLeft: Boolean = SplitScreenPreferences.isPaneOnLeft(activity)
    private var hiddenForFullscreen = false

    var isEnabled: Boolean = false
        private set

    private val layoutWatcher = ViewTreeObserver.OnGlobalLayoutListener {
        // Video fullscreen lives inside the browser half; give it the whole screen while active.
        val fullscreen = binding.fullscreenContainer.isVisible
        if (fullscreen != hiddenForFullscreen) {
            hiddenForFullscreen = fullscreen
            applyVisibility()
        }
        keepMenuButtonInBrowserHalf()
    }

    fun initialize() {
        // Children must not inherit the direction we use on splitRoot to swap sides.
        binding.contentRoot.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        binding.splitPaneRoot.layoutDirection = View.LAYOUT_DIRECTION_LOCALE

        setupToolbar()
        setupDivider()
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(layoutWatcher)

        if (SplitScreenPreferences.isEnabled(activity)) {
            setEnabled(true)
        }
    }

    fun toggle() = setEnabled(!isEnabled)

    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        isEnabled = enabled
        SplitScreenPreferences.setEnabled(activity, enabled)

        if (enabled) {
            ensurePaneWebView()
            paneWebView?.onResume()
        } else {
            // Free the renderer; cookies/logins are kept by the shared CookieManager.
            releasePaneWebView()
        }
        applyLayout()
        onEnabledChanged(enabled)
    }

    fun onResume() {
        paneWebView?.onResume()
    }

    fun onPause() {
        paneWebView?.let { webView ->
            webView.url?.takeIf { BrowserPreferences.isHttpOrHttps(it) }?.let {
                SplitScreenPreferences.setLastUrl(activity, it)
            }
            webView.onPause()
        }
    }

    fun onDestroy() {
        binding.root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutWatcher)
        releasePaneWebView()
    }

    // region Layout

    private fun applyLayout() {
        binding.splitRoot.layoutDirection =
            if (paneOnLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        applyWeights()
        applyVisibility()
    }

    private fun applyVisibility() {
        val showPane = isEnabled && !hiddenForFullscreen
        binding.splitPaneRoot.isVisible = showPane
        binding.splitDivider.isVisible = showPane
    }

    private fun applyWeights() {
        (binding.contentRoot.layoutParams as LinearLayout.LayoutParams).weight =
            if (isEnabled) browserRatio else 1f
        (binding.splitPaneRoot.layoutParams as LinearLayout.LayoutParams).weight =
            1f - browserRatio
        binding.splitRoot.requestLayout()
    }

    /**
     * The floating menu button is anchored to a corner of the whole window. Push it past the
     * pinned pane when they share a side so it never covers e.g. Slack's message composer.
     * Runs on every layout pass because the button's margins are reset by other code.
     */
    private fun keepMenuButtonInBrowserHalf() {
        val fab = binding.fabComposeView
        val params = fab.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val gravity = (params as? CoordinatorLayout.LayoutParams)?.gravity ?: return
        val baseMargin = (BASE_FAB_MARGIN_DP * activity.resources.displayMetrics.density).toInt()

        val absoluteGravity = Gravity.getAbsoluteGravity(gravity, binding.root.layoutDirection)
        val fabOnLeft = (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT
        val paneVisible = binding.splitPaneRoot.isVisible && binding.splitPaneRoot.width > 0
        val offset = if (paneVisible && fabOnLeft == paneOnLeft) {
            binding.splitPaneRoot.width + binding.splitDivider.width
        } else {
            0
        }

        val left = baseMargin + if (fabOnLeft) offset else 0
        val right = baseMargin + if (fabOnLeft) 0 else offset
        // Only touch the params when something changed, otherwise we'd loop on every layout pass.
        if (params.leftMargin != left || params.rightMargin != right) {
            params.leftMargin = left
            params.rightMargin = right
            fab.layoutParams = params
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDivider() {
        val rootLocation = IntArray(2)
        binding.splitDivider.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    binding.splitRoot.getLocationOnScreen(rootLocation)
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val width = binding.splitRoot.width.takeIf { it > 0 } ?: return@setOnTouchListener true
                    val fraction = (event.rawX - rootLocation[0]) / width
                    var ratio = if (paneOnLeft) 1f - fraction else fraction
                    ratio = ratio.coerceIn(SplitScreenPreferences.MIN_RATIO, SplitScreenPreferences.MAX_RATIO)
                    // Snap to an exact half – the most common layout.
                    if (abs(ratio - 0.5f) < SNAP_THRESHOLD) ratio = 0.5f
                    if (ratio != browserRatio) {
                        browserRatio = ratio
                        applyWeights()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    SplitScreenPreferences.setRatio(activity, browserRatio)
                    true
                }
                else -> false
            }
        }
    }

    private fun swapSides() {
        paneOnLeft = !paneOnLeft
        SplitScreenPreferences.setPaneOnLeft(activity, paneOnLeft)
        applyLayout()
    }

    // endregion

    // region Toolbar

    private fun setupToolbar() {
        val actions = SplitPaneToolbarActions(
            onBack = { paneWebView?.takeIf { it.canGoBack() }?.goBack() },
            onReload = { paneWebView?.reload() },
            onHome = { paneWebView?.loadUrl(SplitScreenPreferences.getHomeUrl(activity)) },
            onEditUrl = ::showUrlDialog,
            onToggleDesktop = ::toggleDesktopMode,
            onSwap = ::swapSides,
            onClose = { setEnabled(false) }
        )
        binding.splitPaneToolbar.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AABrowserTheme {
                    SplitPaneToolbar(state = toolbarState, actions = actions)
                }
            }
        }
        toolbarState.isDesktopMode = SplitScreenPreferences.isDesktopMode(activity)
    }

    private fun refreshToolbar() {
        val webView = paneWebView
        toolbarState.canGoBack = webView?.canGoBack() == true
        toolbarState.title = webView?.title?.takeIf { it.isNotBlank() }
            ?: webView?.url?.let { Uri.parse(it).host }.orEmpty()
    }

    private fun toggleDesktopMode() {
        val desktop = !SplitScreenPreferences.isDesktopMode(activity)
        SplitScreenPreferences.setDesktopMode(activity, desktop)
        toolbarState.isDesktopMode = desktop
        paneWebView?.let {
            UserAgentManager.applyBrowserIdentity(it, PANE_USER_AGENT_PROFILE, desktop)
            it.reload()
        }
    }

    private fun showUrlDialog() {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            setText(SplitScreenPreferences.getHomeUrl(activity))
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        fun apply(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return
            val url = BrowserPreferences.formatNavigableUrl(trimmed)
            SplitScreenPreferences.setHomeUrl(activity, url)
            paneWebView?.loadUrl(url)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.split_screen_url_dialog_title)
            .setMessage(R.string.split_screen_url_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.split_screen_url_dialog_save) { _, _ -> apply(input.text.toString()) }
            .setNeutralButton(R.string.split_screen_url_dialog_reset) { _, _ -> apply(SplitScreenPreferences.DEFAULT_SPLIT_URL) }
            .setNegativeButton(R.string.split_screen_url_dialog_cancel, null)
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                apply(input.text.toString())
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.show()
        input.requestFocus()
    }

    // endregion

    // region WebView

    private fun ensurePaneWebView() {
        if (paneWebView != null) return

        val webView = WebView(activity)
        configureWebView(
            webView = webView,
            callbacks = BrowserCallbacks(
                onUrlChange = { url ->
                    if (BrowserPreferences.isHttpOrHttps(url)) {
                        SplitScreenPreferences.setLastUrl(activity, url)
                    }
                    refreshToolbar()
                },
                onTitleChange = { refreshToolbar() },
                onProgressChange = { progress ->
                    toolbarState.progress = progress / 100f
                    if (progress >= 100) refreshToolbar()
                },
                // Links the web app opens in a new window (e.g. shared links in Slack) go
                // to the main browser so the pinned app never navigates away.
                onCreateNewWindow = { createPopupRedirector() },
                onRenderProcessGone = {
                    paneWebView = null
                    if (isEnabled) {
                        ensurePaneWebView()
                    }
                }
            ),
            useDesktopMode = SplitScreenPreferences.isDesktopMode(activity),
            userAgentProfile = PANE_USER_AGENT_PROFILE,
            enableDrmL3Enforcer = false
        )
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.splitPaneWebViewContainer.addView(webView)
        paneWebView = webView

        val startUrl = SplitScreenPreferences.getLastUrl(activity) ?: SplitScreenPreferences.getHomeUrl(activity)
        webView.loadUrl(startUrl)
        refreshToolbar()
    }

    private fun releasePaneWebView() {
        paneWebView?.let { webView ->
            webView.url?.takeIf { BrowserPreferences.isHttpOrHttps(it) }?.let {
                SplitScreenPreferences.setLastUrl(activity, it)
            }
            webView.releaseCompletely()
        }
        paneWebView = null
        binding.splitPaneWebViewContainer.removeAllViews()
    }

    /**
     * A throw-away WebView handed to the engine for `window.open` / `target=_blank`.
     * As soon as it learns the destination URL it forwards it to the main browser.
     */
    private fun createPopupRedirector(): WebView {
        val popup = WebView(activity)
        popup.webViewClient = object : WebViewClient() {
            private var handled = false

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                forward(view, request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                url?.let { forward(view, it) }
            }

            private fun forward(view: WebView, url: String) {
                if (handled || !BrowserPreferences.isHttpOrHttps(url)) return
                handled = true
                view.post {
                    view.stopLoading()
                    view.destroy()
                }
                openInMainBrowser(url)
            }
        }
        return popup
    }

    // endregion

    private companion object {
        const val SNAP_THRESHOLD = 0.03f
        const val BASE_FAB_MARGIN_DP = 16
        val PANE_USER_AGENT_PROFILE = UserAgentProfile.ANDROID_CHROME
    }
}
