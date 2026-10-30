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

package com.kododake.aabrowser

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import com.google.android.material.color.DynamicColors
import com.kododake.aabrowser.AppConstants.REQUEST_CODE_POST_NOTIFICATIONS
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.main.BrowserManagers
import com.kododake.aabrowser.main.FreeDroidWarnHelper
import com.kododake.aabrowser.main.MainActivityBackPressHandler
import com.kododake.aabrowser.main.MainActivityCallbackFactory
import com.kododake.aabrowser.main.MenuFabController
import com.kododake.aabrowser.main.WebViewWarmupHelper
import com.kododake.aabrowser.media.WebViewMediaSessionBridge
import com.kododake.aabrowser.model.QuickActionButtonMode
import com.kododake.aabrowser.tabs.BrowserTab
import com.kododake.aabrowser.ui.MainActivitySetup
import com.kododake.aabrowser.ui.controllers.NavigationButtonUpdater
import com.kododake.aabrowser.ui.controllers.ProgressIndicatorController

class MainActivity : AppCompatActivity(), MainActivityCallbackFactory.CallbackHost {

    private lateinit var binding: ActivityMainBinding

    private val isDebugBuild: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val managers: BrowserManagers by lazy {
        BrowserManagers(
            activity = this,
            binding = binding,
            host = this,
            isDebugBuild = isDebugBuild,
            onUrlChanged = ::onUrlChanged,
            onTitleChanged = ::onTitleChanged,
            onProgressChanged = ::updateProgress,
            onNavigationButtonsUpdateNeeded = ::updateNavigationButtons
        )
    }

    private val menuFabController: MenuFabController by lazy {
        MenuFabController(
            context = this,
            binding = binding,
            menuHelper = managers.uiManager.menuHelper,
            isShowingStartPage = { managers.startPageManager.isShowingStartPage },
            isInFullscreen = { managers.uiManager.isInFullscreen() }
        )
    }

    private val progressController: ProgressIndicatorController by lazy {
        ProgressIndicatorController(binding.progressComposeView)
    }

    var webView: WebView? = null
        private set

    private val mediaHandler = Handler(Looper.getMainLooper())
    private var webViewMediaSessionBridge: WebViewMediaSessionBridge? = null
    private val webStateFetcher = object : Runnable {
        override fun run() {
            if (isDestroyed || isFinishing) return
            webView?.evaluateJavascript(
                """
                (function(){
                    var medias = document.querySelectorAll('video, audio');
                    for(var i=0; i<medias.length; i++) {
                        if(!medias[i].paused && !medias[i].muted) {
                            var dur = isNaN(medias[i].duration) ? 0 : medias[i].duration;
                            return medias[i].currentTime + ',' + dur + ',' + medias[i].paused;
                        }
                    }
                    if(medias.length > 0) {
                        var dur = isNaN(medias[0].duration) ? 0 : medias[0].duration;
                        return medias[0].currentTime + ',' + dur + ',' + medias[0].paused;
                    }
                    return '';
                })();
                """.trimIndent()
            ) { result ->
                if (!result.isNullOrBlank() && result != "\"\"" && result != "null") {
                    runCatching {
                        val parts = result.replace("\"", "").split(",")
                        if (parts.size == 3) {
                            val current = parts[0].toDouble()
                            val dur = parts[1].toDouble()
                            val paused = parts[2].toBoolean()
                            webViewMediaSessionBridge?.updatePlaybackState(current, dur, paused)
                        }
                    }
                }
            }
            mediaHandler.postDelayed(this, 1000L)
        }
    }

    override var currentUrl: String = ""
        private set

    override var currentPageTitle: String = ""
        private set

    private var shouldForceSessionRestore: Boolean = false

    var latestReleaseUrl: String = "https://github.com/kododake/AABrowser/releases"
        private set

    private val pickStartPageBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        managers.startPageManager.handleStartPageBackgroundPicked(uri)
        onRebuildSettingsContent()
    }

    override fun attachBaseContext(newBase: Context?) {
        val scaled = newBase?.let { BrowserPreferences.createScaledContext(it) }
        super.attachBaseContext(scaled ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WebViewWarmupHelper.warmup(this)
        DynamicColors.applyToActivityIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(BrowserPreferences.getThemeMode(this).nightMode)
        BrowserPreferences.ensureGameBookmarkMigrated(this)
        super.onCreate(savedInstanceState)

        shouldForceSessionRestore = (savedInstanceState != null)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        managers.umamiTracker.trackEvent("app_open")
        setupUi()
        setupBackPressHandling()
        setupMediaSessionBridge()

        managers.permissionManager.ensureNotificationPermissionIfNeeded(REQUEST_CODE_POST_NOTIFICATIONS)
        FreeDroidWarnHelper.checkAndShow(this, managers.themeManager.resolveThemeColor(androidx.appcompat.R.attr.colorError)) { url ->
            managers.navigationManager.loadUrlFromIntent(url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        managers.navigationManager.extractBrowsableUrl(intent)?.let {
            managers.navigationManager.loadUrlFromIntent(it)
        }
    }

    override fun onStart() {
        super.onStart()
        webViewMediaSessionBridge?.onHostResume()
    }

    override fun onResume() {
        super.onResume()
        mediaHandler.post(webStateFetcher)
        webView?.onResume()
        managers.themeManager.applyMenuHeaderColors()
        managers.bookmarkManager.refreshBookmarks()
        managers.tabManager.refreshTabs()
        managers.startPageManager.refreshStartPage()
        syncUserAgentProfile()
        managers.uiManager.applyQuickActionButtonPreferences()
    }

    override fun onPause() {
        mediaHandler.removeCallbacks(webStateFetcher)
        managers.uiManager.exitFullscreen()
        webView?.onPause()
        managers.tabManager.persistTabSession()
        super.onPause()
    }

    override fun onStop() {
        webViewMediaSessionBridge?.onHostPause()
        super.onStop()
    }

    override fun onDestroy() {
        mediaHandler.removeCallbacks(webStateFetcher)
        menuFabController.cleanup()
        managers.uiManager.exitFullscreen()
        webViewMediaSessionBridge?.release()
        webViewMediaSessionBridge = null
        managers.startPageManager.onDestroy()
        managers.tabManager.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        managers.permissionManager.handleRequestPermissionsResult(requestCode, grantResults) { granted ->
            val speechTab = managers.tabManager.browserTabs.firstOrNull { it.id == managers.permissionManager.pendingSpeechBridgeTabId }
            speechTab?.speechBridge?.onPermissionResult(granted)
        }
    }

    private fun setupUi() {
        val setup = MainActivitySetup(
            activity = this,
            binding = binding,
            browserManagers = managers,
            actions = MainActivitySetup.Actions(
                getWebView = { webView },
                getCurrentUrl = { currentUrl },
                getLatestReleaseUrl = { latestReleaseUrl },
                updateNavigationButtons = ::updateNavigationButtons,
                handleQuickActionButtonPressed = ::onQuickActionButtonPressed,
                showStartPage = ::showStartPage,
                onDesktopModeChanged = { isChecked ->
                    managers.tabManager.updateDesktopMode(isChecked, BrowserPreferences.getUserAgentProfile(this))
                }
            )
        )
        setup.initializeUi(
            intentUrl = managers.navigationManager.extractBrowsableUrl(intent),
            shouldForceSessionRestore = shouldForceSessionRestore
        )

        updateNavigationButtons()
        managers.tabManager.refreshTabs()
        managers.startPageManager.refreshStartPage()
        menuFabController.showMenuButtonTemporarily()
        managers.bookmarkManager.refreshBookmarks()
        managers.uiManager.applyQuickActionButtonPreferences()
    }

    private fun setupBackPressHandling() {
        MainActivityBackPressHandler.register(
            activity = this,
            overlayCoordinator = managers.overlayCoordinator,
            uiManager = managers.uiManager,
            tabManager = managers.tabManager,
            startPageManager = managers.startPageManager,
            getCurrentUrl = { currentUrl },
            onHideStartPage = ::hideStartPage,
            onNavigationButtonsUpdateNeeded = ::updateNavigationButtons
        )
    }

    private fun showStartPage() {
        managers.startPageManager.showStartPage()
        webView?.visibility = View.INVISIBLE
    }

    private fun hideStartPage() {
        managers.startPageManager.hideStartPage(currentPageTitle, currentUrl)
        webView?.visibility = View.VISIBLE
    }

    private fun updateNavigationButtons() {
        NavigationButtonUpdater.update(
            binding = binding,
            isShowingStartPage = managers.startPageManager.isShowingStartPage,
            currentUrl = currentUrl,
            webView = webView,
            menuHelper = managers.uiManager.menuHelper
        )
    }

    private fun updateProgress(p: Int) = progressController.updateProgress(p)

    override fun onShowMenuButtonTemporarily() = menuFabController.showMenuButtonTemporarily()

    override fun onHomePagePreferenceChanged() {
        managers.bookmarkManager.refreshBookmarks()
        managers.startPageManager.refreshStartPage()
        onRebuildSettingsContent()
        val url = BrowserPreferences.getHomePageUrl(this)
        if (!url.isNullOrBlank() && managers.startPageManager.isShowingStartPage) {
            managers.navigationManager.loadUrlFromIntent(url)
        } else {
            updateNavigationButtons()
        }
    }

    override fun onRebuildSettingsContent() {
        if (::binding.isInitialized && binding.settingsComposeView.isVisible) {
            managers.overlayManager.showSettingsView()
        }
    }

    override fun onQuickActionButtonPressed() {
        val mode = BrowserPreferences.getQuickActionButtonMode(this)
        managers.uiManager.showMenuOverlay(focusAddressBar = (mode != QuickActionButtonMode.MENU))
    }

    private fun syncUserAgentProfile() {
        managers.tabManager.updateUserAgentProfile(
            BrowserPreferences.getUserAgentProfile(this),
            BrowserPreferences.shouldUseDesktopMode(this)
        )
    }
    override fun onUrlChanged(url: String) {
        currentUrl = url
        webViewMediaSessionBridge?.updateNowPlaying(currentPageTitle, currentUrl)
    }
    override fun onTitleChanged(title: String) {
        currentPageTitle = title
        managers.bookmarkManager.updateBookmarkTitleIfBetter(currentUrl, title)
        webViewMediaSessionBridge?.updateNowPlaying(currentPageTitle, currentUrl)
    }
    override fun onTabChanged(tab: BrowserTab) {
        webView = tab.webView
        currentUrl = tab.currentUrl
        currentPageTitle = tab.currentTitle
        managers.bookmarkManager.updateBookmarkTitleIfBetter(tab.currentUrl, tab.currentTitle)
        managers.uiManager.menuHelper.updatePage(tab.currentUrl, tab.currentTitle)
        webViewMediaSessionBridge?.updateNowPlaying(currentPageTitle, currentUrl)
    }
    override fun onShowStartPage() { showStartPage() }
    override fun onHideStartPage() { hideStartPage() }
    override fun onUpdateNavigationButtons() { updateNavigationButtons() }
    override fun onPickBackgroundRequested() { pickStartPageBackgroundLauncher.launch(arrayOf("image/*")) }
    override fun onVersionInfoReceived(latestUrl: String, tagName: String) { latestReleaseUrl = latestUrl }
    override fun onRecreateRequested() { recreate() }

    private fun setupMediaSessionBridge() {
        webViewMediaSessionBridge = WebViewMediaSessionBridge(
            context = this,
            onPlayRequested = {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)m[i].play();})();",
                        null
                    )
                }
            },
            onPauseRequested = {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)m[i].pause();})();",
                        null
                    )
                }
            },
            onSkipForwardRequested = {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        """
                        (function(){
                            var controls = document.querySelectorAll('button, [role="button"], [aria-label], [title], [data-tooltip]');
                            for (var i = 0; i < controls.length; i++) {
                                var el = controls[i];
                                var label = (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('data-tooltip') || el.textContent || '').toLowerCase();
                                if (/next|next track|skip/.test(label)) { el.click(); return; }
                            }
                            var medias = document.querySelectorAll('video, audio');
                            for (var i = 0; i < medias.length; i++) medias[i].currentTime += 10;
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            },
            onSkipBackwardRequested = {
                runOnUiThread {
                    webView?.evaluateJavascript(
                        """
                        (function(){
                            var controls = document.querySelectorAll('button, [role="button"], [aria-label], [title], [data-tooltip]');
                            for (var i = 0; i < controls.length; i++) {
                                var el = controls[i];
                                var label = (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('data-tooltip') || el.textContent || '').toLowerCase();
                                if (/previous|prev|back/.test(label)) { el.click(); return; }
                            }
                            var medias = document.querySelectorAll('video, audio');
                            for (var i = 0; i < medias.length; i++) medias[i].currentTime -= 10;
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            },
            onSeekRequested = { positionMs ->
                runOnUiThread {
                    webView?.evaluateJavascript(
                        "(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)m[i].currentTime=${positionMs / 1000.0};})();",
                        null
                    )
                }
            }
        )
        webViewMediaSessionBridge?.updateNowPlaying(currentPageTitle, currentUrl)
    }
}
