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

package com.kododake.aabrowser.car

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kododake.aabrowser.AppConstants
import com.kododake.aabrowser.BuildConfig
import com.kododake.aabrowser.R
import com.kododake.aabrowser.bookmarks.BookmarkIconUtils
import com.kododake.aabrowser.bookmarks.BookmarkUrlFormatter
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.model.QuickActionButtonMode
import com.kododake.aabrowser.model.QuickActionButtonPosition
import com.kododake.aabrowser.ui.QRUtils
import com.kododake.aabrowser.ui.compose.screens.bookmarks.BookmarkActions
import com.kododake.aabrowser.ui.compose.screens.bookmarks.BookmarkItemUi
import com.kododake.aabrowser.ui.compose.screens.bookmarks.BookmarkManagerSheet
import com.kododake.aabrowser.ui.compose.screens.menu.MenuActions
import com.kododake.aabrowser.ui.compose.screens.startpage.StartPageScreenCallbacks
import com.kododake.aabrowser.ui.compose.screens.startpage.StartPageSlotUi
import com.kododake.aabrowser.ui.compose.screens.startpage.StartPageViews
import com.kododake.aabrowser.ui.controllers.MenuSetupHelper
import com.kododake.aabrowser.ui.controllers.ProgressIndicatorController
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.configureWebView
import com.kododake.aabrowser.web.releaseCompletely

/**
 * Renders AABrowser's original UI (Start Page, WebView, floating Menu FAB, Menu Sheet,
 * Bookmarks Sheet) onto the surface provided by Android Auto's multi-region template host
 * so AABrowser can run side-by-side with Waze in split-screen mode.
 */
class AABrowserCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var presentationLifecycleOwner: PresentationLifecycleOwner? = null
    private var binding: ActivityMainBinding? = null
    private var webView: WebView? = null
    private var progressController: ProgressIndicatorController? = null
    private val menuHelper = MenuSetupHelper()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideFabRunnable = Runnable {
        if (!isShowingStartPage && !BrowserPreferences.isQuickActionButtonAlwaysVisible(carContext)) {
            menuHelper.hideFab()
        }
    }

    private var isShowingStartPage: Boolean = true
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var composeStartPageView: View? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val bookmarkSheetVisible = mutableStateOf(false)
    private val bookmarkItemsState = mutableStateOf<List<BookmarkItemUi>>(emptyList())
    private val canAddBookmarkState = mutableStateOf(false)
    private val currentBookmarkUrlState = mutableStateOf("")

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                tearDownPresentation()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .setLoading(true)
            .build()
        val contentTemplate = PaneTemplate.Builder(pane).build()

        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        val mapController = MapController.Builder()
            .setMapActionStrip(mapActionStrip)
            .setPanModeListener { _ -> }
            .build()

        return MapWithContentTemplate.Builder()
            .setContentTemplate(contentTemplate)
            .setMapController(mapController)
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        if (surfaceContainer.width <= 0 || surfaceContainer.height <= 0) return

        val existingDisplay = virtualDisplay
        if (existingDisplay != null && presentation != null) {
            existingDisplay.resize(surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
            existingDisplay.surface = surface
            return
        }

        createVirtualDisplay(surfaceContainer)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        virtualDisplay?.surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) = Unit

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onClick(x: Float, y: Float) {
        val decorView = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        decorView.dispatchTouchEvent(down)
        decorView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        if (!isShowingStartPage && binding?.menuOverlay?.isVisible != true) {
            showMenuButtonTemporarily()
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!isShowingStartPage && binding?.menuOverlay?.isVisible != true) {
            webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
            showMenuButtonTemporarily()
        } else {
            val decorView = presentation?.window?.decorView ?: return
            val cx = decorView.width / 2f
            val cy = decorView.height / 2f
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0)
            val move = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, cx - distanceX, cy - distanceY, 0)
            val up = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, cx - distanceX, cy - distanceY, 0)
            decorView.dispatchTouchEvent(down)
            decorView.dispatchTouchEvent(move)
            decorView.dispatchTouchEvent(up)
            down.recycle()
            move.recycle()
            up.recycle()
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val target = webView ?: return
        if (!isShowingStartPage) {
            if (scaleFactor > 1.05f) {
                target.zoomIn()
            } else if (scaleFactor < 0.95f) {
                target.zoomOut()
            }
        }
    }

    private fun createVirtualDisplay(surfaceContainer: SurfaceContainer) {
        tearDownPresentation()

        val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        virtualDisplay = displayManager.createVirtualDisplay(
            "AABrowserCarSurface",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surfaceContainer.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )

        val display = virtualDisplay?.display ?: return
        val scaledContext = BrowserPreferences.createScaledContext(carContext)
        val themedContext = ContextThemeWrapper(scaledContext, R.style.Theme_AABrowser)

        val owner = PresentationLifecycleOwner().also {
            it.onCreate()
            presentationLifecycleOwner = it
        }

        presentation = object : Presentation(themedContext, display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                window?.decorView?.let { decor ->
                    decor.setViewTreeLifecycleOwner(owner)
                    decor.setViewTreeViewModelStoreOwner(owner)
                    decor.setViewTreeSavedStateRegistryOwner(owner)
                }

                val inflated = ActivityMainBinding.inflate(LayoutInflater.from(themedContext))
                binding = inflated
                setContentView(inflated.root)

                setupBrowserUi(themedContext, inflated)
            }
        }.also { it.show() }
    }

    private fun setupBrowserUi(uiContext: Context, inflated: ActivityMainBinding) {
        BrowserPreferences.ensureGameBookmarkMigrated(carContext)

        progressController = ProgressIndicatorController(inflated.progressComposeView)

        val desktopMode = BrowserPreferences.shouldUseDesktopMode(carContext)
        val profile = BrowserPreferences.getUserAgentProfile(carContext)
        val callbacks = BrowserCallbacks(
            onUrlChange = { url ->
                currentUrl = url
                if (BrowserPreferences.isHttpOrHttps(url)) {
                    BrowserPreferences.persistUrl(carContext, url)
                }
                if (!isShowingStartPage) {
                    menuHelper.updatePage(url, currentTitle)
                }
                updateNavigationState()
            },
            onTitleChange = { title ->
                currentTitle = title.orEmpty()
                if (!isShowingStartPage) {
                    menuHelper.updatePage(currentUrl, currentTitle)
                }
            },
            onProgressChange = { progress ->
                if (!isShowingStartPage) {
                    progressController?.updateProgress(progress)
                }
                if (progress >= 100) {
                    updateNavigationState()
                }
            },
            onEnterFullscreen = { view, callback ->
                enterCustomFullscreen(view, callback)
            },
            onExitFullscreen = {
                exitCustomFullscreen(fromWebChrome = true)
            }
        )

        val carWebView = WebView(uiContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    showMenuButtonTemporarily()
                }
                false
            }
        }
        inflated.webViewContainer.addView(carWebView)
        webView = carWebView

        configureWebView(
            webView = carWebView,
            callbacks = callbacks,
            useDesktopMode = desktopMode,
            userAgentProfile = profile
        )

        setupMenuAndOverlays(uiContext, inflated)
        applyQuickActionButtonPreferences(uiContext, inflated)

        inflated.commonScrimView.setOnClickListener {
            hideAllOverlays()
        }

        val shouldResume = BrowserPreferences.shouldResumeLastPageOnLaunch(carContext)
        val lastVisited = BrowserPreferences.getLastVisitedUrl(carContext)
        val homePage = BrowserPreferences.getHomePageUrl(carContext)

        when {
            shouldResume && !lastVisited.isNullOrBlank() -> navigateToAddress(lastVisited)
            !homePage.isNullOrBlank() -> navigateToAddress(homePage)
            else -> showStartPage(uiContext)
        }
    }

    private fun setupMenuAndOverlays(uiContext: Context, inflated: ActivityMainBinding) {
        val menuActions = MenuActions(
            onBack = {
                if (customView != null) {
                    exitCustomFullscreen(fromWebChrome = false)
                } else {
                    webView?.let {
                        if (it.canGoBack()) {
                            it.goBack()
                        } else if (!isShowingStartPage) {
                            showStartPage(uiContext)
                        }
                    }
                }
                updateNavigationState()
            },
            onForward = {
                webView?.let { if (it.canGoForward()) it.goForward() }
                updateNavigationState()
            },
            onReload = {
                if (customView != null) {
                    exitCustomFullscreen(fromWebChrome = false)
                }
                if (isShowingStartPage) {
                    refreshStartPage(uiContext)
                } else {
                    webView?.reload()
                }
                hideMenuOverlay()
            },
            onHome = {
                if (customView != null) {
                    exitCustomFullscreen(fromWebChrome = false)
                }
                showStartPage(uiContext)
                hideMenuOverlay()
            },
            onDesktopToggle = { enabled ->
                BrowserPreferences.setDesktopMode(carContext, enabled)
                menuHelper.stateHolder.isDesktopMode = enabled
                if (!isShowingStartPage) {
                    webView?.reload()
                }
                updateNavigationState()
            },
            onFullscreenToggle = { enabled ->
                if (!enabled && customView != null) {
                    exitCustomFullscreen(fromWebChrome = false)
                }
                BrowserPreferences.setFullscreenMode(carContext, enabled)
                menuHelper.stateHolder.isFullscreenMode = enabled
            },
            onNewTab = {
                showStartPage(uiContext)
                hideMenuOverlay()
            },
            onTabs = {
                showStartPage(uiContext)
                hideMenuOverlay()
            },
            onBookmarks = {
                openBookmarksSheet(uiContext)
            },
            onNavigate = { raw ->
                navigateToAddress(raw)
                hideMenuOverlay()
            },
            onClose = {
                hideMenuOverlay()
            },
            onDismissFinished = {
                onMenuDismissFinished()
            }
        )

        menuHelper.setup(inflated.menuComposeView, menuActions)
        menuHelper.setupFab(inflated.fabComposeView) {
            showMenuOverlay(uiContext)
        }
        menuHelper.updateVersion("v${BuildConfig.VERSION_NAME}")
        menuHelper.stateHolder.isFullscreenMode = BrowserPreferences.shouldUseFullscreenMode(carContext)

        inflated.bookmarkComposeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BookmarkManagerSheet(
                    isVisible = bookmarkSheetVisible.value,
                    bookmarks = bookmarkItemsState.value,
                    canAddCurrentUrl = canAddBookmarkState.value,
                    currentUrl = currentBookmarkUrlState.value,
                    animateEnter = true,
                    keepScrimOnClose = false,
                    onProgress = {},
                    actions = BookmarkActions(
                        onSelectBookmark = { url ->
                            bookmarkSheetVisible.value = false
                            inflated.bookmarkComposeView.visibility = View.GONE
                            hideAllOverlays()
                            navigateToAddress(url)
                        },
                        onDeleteBookmark = { url ->
                            BrowserPreferences.removeBookmark(carContext, url)
                            refreshBookmarksState()
                            refreshStartPage(uiContext)
                        },
                        onPinBookmark = { url, slot ->
                            if (slot >= 0) {
                                BrowserPreferences.clearStartPageSlot(carContext, slot)
                            } else {
                                val slots = BrowserPreferences.getStartPageSlots(carContext)
                                val emptySlot = slots.indexOfFirst { it.isNullOrBlank() }
                                if (emptySlot >= 0) {
                                    BrowserPreferences.setStartPageSlot(carContext, emptySlot, url)
                                }
                            }
                            refreshBookmarksState()
                            refreshStartPage(uiContext)
                        },
                        onAddCurrentPage = {
                            val url = currentUrl.trim()
                            if (!isShowingStartPage && BookmarkUrlFormatter.isActiveWebsiteUrl(url)) {
                                val title = currentTitle.trim().ifBlank { BookmarkUrlFormatter.displayTitleForUrl(url) }
                                BrowserPreferences.addBookmark(carContext, url, title)
                                refreshBookmarksState()
                            }
                        },
                        onReorderBookmarks = { from, to ->
                            val list = bookmarkItemsState.value.toMutableList()
                            if (from in list.indices && to in list.indices && from != to) {
                                val moved = list.removeAt(from)
                                list.add(to, moved)
                                bookmarkItemsState.value = list
                            }
                        },
                        onCommitBookmarkReorder = {
                            BrowserPreferences.setBookmarks(carContext, bookmarkItemsState.value.map { it.url })
                            refreshStartPage(uiContext)
                        },
                        onClose = {
                            bookmarkSheetVisible.value = false
                        },
                        onDismiss = {
                            bookmarkSheetVisible.value = false
                        },
                        onDismissFinished = {
                            if (!bookmarkSheetVisible.value) {
                                inflated.bookmarkComposeView.visibility = View.GONE
                                hideAllOverlays()
                            }
                        }
                    ),
                    faviconProvider = { url ->
                        BookmarkIconUtils.resolveCachedSiteIcon(carContext, url) {
                            refreshBookmarksState()
                        }
                    }
                )
            }
        }
    }

    private fun openBookmarksSheet(uiContext: Context) {
        val inflated = binding ?: return
        refreshBookmarksState()
        menuHelper.hideMenu()
        inflated.menuComposeView.visibility = View.GONE
        inflated.menuOverlay.visibility = View.VISIBLE
        inflated.bookmarkComposeView.visibility = View.VISIBLE
        bookmarkSheetVisible.value = true
    }

    private fun refreshBookmarksState() {
        val url = currentUrl.trim()
        canAddBookmarkState.value = !isShowingStartPage && BookmarkUrlFormatter.isActiveWebsiteUrl(url)
        currentBookmarkUrlState.value = url
        val entries = BrowserPreferences.getBookmarkEntries(carContext)
        val slots = BrowserPreferences.getStartPageSlots(carContext)
        bookmarkItemsState.value = entries.map { entry ->
            BookmarkItemUi(
                url = entry.url,
                title = entry.title.ifBlank { BookmarkUrlFormatter.displayTitleForUrl(entry.url) },
                slotIndex = slots.indexOf(entry.url)
            )
        }
    }

    private fun applyQuickActionButtonPreferences(uiContext: Context, inflated: ActivityMainBinding) {
        val mode = BrowserPreferences.getQuickActionButtonMode(carContext)
        menuHelper.setFabMode(mode == QuickActionButtonMode.ADDRESS_BAR)
        val density = uiContext.resources.displayMetrics.density
        val margin = (16 * density).toInt()
        val position = BrowserPreferences.getQuickActionButtonPosition(carContext)
        val layoutParams = inflated.fabComposeView.layoutParams as? CoordinatorLayout.LayoutParams
        if (layoutParams != null) {
            layoutParams.gravity = when (position) {
                QuickActionButtonPosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                QuickActionButtonPosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
                QuickActionButtonPosition.TOP_LEFT -> Gravity.TOP or Gravity.START
                QuickActionButtonPosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
            }
            layoutParams.setMargins(margin, margin, margin, margin)
            inflated.fabComposeView.layoutParams = layoutParams
        }
        menuHelper.showFab()
    }

    private fun showMenuOverlay(uiContext: Context) {
        val inflated = binding ?: return
        menuHelper.updatePage(
            url = if (isShowingStartPage) "" else currentUrl,
            title = if (isShowingStartPage) "" else currentTitle
        )
        updateNavigationState()
        inflated.menuOverlay.visibility = View.VISIBLE
        inflated.menuComposeView.visibility = View.VISIBLE
        inflated.commonScrimView.setBackgroundColor(0x66000000)
        inflated.commonScrimView.alpha = 1f
        menuHelper.showMenu()
        menuHelper.hideFab()
    }

    private fun hideMenuOverlay() {
        menuHelper.hideMenu()
    }

    private fun onMenuDismissFinished() {
        val inflated = binding ?: return
        if (!menuHelper.stateHolder.isMenuVisible) {
            inflated.menuComposeView.visibility = View.GONE
            if (!inflated.bookmarkComposeView.isVisible) {
                inflated.menuOverlay.visibility = View.GONE
                inflated.commonScrimView.alpha = 0f
                showMenuButtonTemporarily()
            }
        }
    }

    private fun hideAllOverlays() {
        val inflated = binding ?: return
        bookmarkSheetVisible.value = false
        menuHelper.hideMenu()
        inflated.bookmarkComposeView.visibility = View.GONE
        inflated.menuComposeView.visibility = View.GONE
        inflated.menuOverlay.visibility = View.GONE
        inflated.commonScrimView.alpha = 0f
        showMenuButtonTemporarily()
    }

    private fun showMenuButtonTemporarily() {
        mainHandler.removeCallbacks(hideFabRunnable)
        menuHelper.showFab()
        if (!isShowingStartPage && !BrowserPreferences.isQuickActionButtonAlwaysVisible(carContext)) {
            mainHandler.postDelayed(hideFabRunnable, 4000L)
        }
    }

    private fun showStartPage(uiContext: Context) {
        val inflated = binding ?: return
        isShowingStartPage = true
        populateStartPageView(uiContext, inflated)
        inflated.startPageRoot.visibility = View.VISIBLE
        webView?.visibility = View.INVISIBLE
        progressController?.updateProgress(0)
        menuHelper.updatePage("", "")
        updateNavigationState()
        mainHandler.removeCallbacks(hideFabRunnable)
        menuHelper.showFab()
    }

    private fun hideStartPage() {
        val inflated = binding ?: return
        isShowingStartPage = false
        inflated.startPageRoot.visibility = View.GONE
        webView?.visibility = View.VISIBLE
        updateNavigationState()
        showMenuButtonTemporarily()
    }

    private fun refreshStartPage(uiContext: Context) {
        val inflated = binding ?: return
        composeStartPageView?.let { inflated.startPageRoot.removeView(it) }
        composeStartPageView = null
        if (isShowingStartPage) {
            populateStartPageView(uiContext, inflated)
        }
    }

    private fun populateStartPageView(uiContext: Context, inflated: ActivityMainBinding) {
        composeStartPageView?.let { inflated.startPageRoot.removeView(it) }
        val entries = BrowserPreferences.getBookmarkEntries(carContext)
        val slots = BrowserPreferences.getStartPageSlots(carContext).mapIndexed { index, url ->
            val cleanUrl = url.orEmpty()
            val title = if (cleanUrl.isNotBlank()) {
                val target = cleanUrl.trim().trimEnd('/')
                entries.firstOrNull { it.url.trim().trimEnd('/') == target }
                    ?.title?.takeIf { it.isNotBlank() }
                    ?: BookmarkUrlFormatter.displayTitleForUrl(cleanUrl)
            } else {
                ""
            }
            val label = if (cleanUrl.isNotBlank()) BookmarkUrlFormatter.displayLabelForUrl(cleanUrl) else ""
            StartPageSlotUi(
                id = index,
                index = index,
                url = url,
                title = title,
                label = label
            )
        }
        val hasResumePage = !BrowserPreferences.getLastVisitedUrl(carContext).isNullOrBlank()
        val qrBitmap = QRUtils.generateQrCode("https://github.com/sponsors/kododake", 200)

        val callbacks = StartPageScreenCallbacks(
            onNavigate = { raw -> navigateToAddress(raw) },
            onSlotClick = { _, url ->
                if (url.isNullOrBlank()) {
                    openBookmarksSheet(uiContext)
                } else {
                    navigateToAddress(url)
                }
            },
            onMoveSlot = { fromIdx, toIdx ->
                val currentSlots = BrowserPreferences.getStartPageSlots(carContext).toMutableList()
                if (fromIdx in currentSlots.indices && toIdx in currentSlots.indices && fromIdx != toIdx) {
                    val moved = currentSlots.removeAt(fromIdx)
                    currentSlots.add(toIdx, moved)
                    BrowserPreferences.setStartPageSlots(carContext, currentSlots)
                    refreshStartPage(uiContext)
                }
            },
            onClearSlot = { idx ->
                BrowserPreferences.clearStartPageSlot(carContext, idx)
                refreshStartPage(uiContext)
            },
            onResumeClick = {
                val last = BrowserPreferences.getLastVisitedUrl(carContext)
                if (!last.isNullOrBlank()) {
                    navigateToAddress(last)
                }
            },
            onOpenSponsors = { url ->
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    carContext.startActivity(intent)
                }.onFailure {
                    Toast.makeText(carContext, R.string.error_generic_message, Toast.LENGTH_SHORT).show()
                }
            },
            onOpenAAAnything = {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConstants.AA_ANYTHING_LATEST_RELEASE_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    carContext.startActivity(intent)
                }.onFailure {
                    Toast.makeText(carContext, R.string.error_generic_message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismissAAAnything = {
                BrowserPreferences.setShowAAAnythingPromotion(carContext, false)
            }
        )

        val view = StartPageViews.createStartPageContent(
            context = uiContext,
            slots = slots,
            hasResumePage = hasResumePage,
            sponsorsQrBitmap = qrBitmap,
            callbacks = callbacks
        )
        composeStartPageView = view
        inflated.startPageRoot.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun navigateToAddress(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val formatted = BrowserPreferences.formatNavigableUrl(trimmed)
        hideStartPage()
        currentUrl = formatted
        if (BrowserPreferences.isHttpOrHttps(formatted)) {
            BrowserPreferences.persistUrl(carContext, formatted)
        }
        webView?.loadUrl(formatted)
        updateNavigationState()
    }

    private fun updateNavigationState() {
        val wv = webView
        val canBack = (customView != null) || (wv?.canGoBack() == true) || !isShowingStartPage
        val canForward = (wv?.canGoForward() == true) && !isShowingStartPage
        val canReload = !isShowingStartPage
        val desktop = BrowserPreferences.shouldUseDesktopMode(carContext)
        menuHelper.updateNavigation(
            canBack = canBack,
            canForward = canForward,
            canRel = canReload,
            desktop = desktop,
            canQr = false
        )
    }

    private fun enterCustomFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback
    ) {
        val inflated = binding ?: run {
            callback.onCustomViewHidden()
            return
        }
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }

        (view.parent as? ViewGroup)?.removeView(view)
        customView = view
        customViewCallback = callback

        if (inflated.menuOverlay.isVisible) {
            hideMenuOverlay()
        }
        menuHelper.hideFab()
        webView?.visibility = View.INVISIBLE

        inflated.fullscreenContainer.apply {
            visibility = View.VISIBLE
            removeAllViews()
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            bringToFront()
        }
        menuHelper.stateHolder.isFullscreenMode = true
        updateNavigationState()
    }

    private fun exitCustomFullscreen(fromWebChrome: Boolean = false) {
        if (customView == null) return
        val inflated = binding

        inflated?.fullscreenContainer?.apply {
            removeAllViews()
            visibility = View.GONE
        }

        webView?.visibility = if (isShowingStartPage) View.INVISIBLE else View.VISIBLE

        val callback = customViewCallback
        customView = null
        customViewCallback = null

        if (!fromWebChrome) {
            callback?.onCustomViewHidden()
        }
        menuHelper.stateHolder.isFullscreenMode = BrowserPreferences.shouldUseFullscreenMode(carContext)
        updateNavigationState()
        showMenuButtonTemporarily()
    }

    private fun tearDownPresentation() {
        mainHandler.removeCallbacks(hideFabRunnable)
        exitCustomFullscreen(fromWebChrome = false)
        composeStartPageView = null
        progressController = null
        webView?.releaseCompletely()
        webView = null
        binding = null
        presentation?.dismiss()
        presentation = null
        presentationLifecycleOwner?.onDestroy()
        presentationLifecycleOwner = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private class PresentationLifecycleOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val viewModelStore: ViewModelStore
            get() = store

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
