package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlin.math.min
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import uy.kohesive.injekt.injectLazy

/**
 * Continuous (webtoon-style) viewer for text/novel chapters. Each chapter is a single
 * [ReaderPage] rendered in a [TextPageHolder]; chapters flow seamlessly in a [RecyclerView].
 */
@SuppressLint("ClickableViewAccessibility")
class TextViewer(val activity: ReaderActivity) : BaseViewer {

    private val scope = MainScope()
    val downloadManager: DownloadManager by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    private val layoutManager = LinearLayoutManager(activity)
    private val adapter = TextAdapter(this)
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4
    private val menuThreshold = 10.dpToPx
    private var currentPage: Any? = null

    val readerTheme get() = preferences.readerTheme().get()

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                activity.toggleMenu()
                return true
            }
        },
    )

    private val recycler = RecyclerView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        layoutManager = this@TextViewer.layoutManager
        adapter = this@TextViewer.adapter
        itemAnimator = null
        isVisible = false
    }

    init {
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()
                    if (dy > menuThreshold || dy < -menuThreshold) activity.hideMenu()
                    if (dy < 0) {
                        val firstItem = adapter.items.getOrNull(layoutManager.findFirstVisibleItemPosition())
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }
                }
            },
        )
        recycler.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    gestureDetector.onTouchEvent(e)
                    return false
                }
            },
        )
        preferences.readerTheme().changesIn(scope) {
            if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    override fun getView(): View = recycler

    override fun destroy() {
        scope.cancel()
    }

    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = preferences.alwaysShowChapterTransition().get() || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            onScrolled(position)
        }
    }

    private fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findFirstVisibleItemPosition()
        val item = adapter.items.getOrNull(position) ?: return
        if (currentPage == item) return
        currentPage = item
        when (item) {
            is ReaderPage -> {
                activity.onPageSelected(item, false)
                if (item.chapter == adapter.currentChapter) {
                    val nextItem = adapter.items.lastOrNull()
                    val nextChapter = (nextItem as? ChapterTransition.Next)?.to
                        ?: (nextItem as? ReaderPage)?.chapter
                    if (nextChapter != null) activity.requestPreloadChapter(nextChapter)
                }
            }
            is ChapterTransition -> item.to?.let { activity.requestPreloadChapter(it) }
        }
    }

    override fun moveToNext() {
        recycler.smoothScrollBy(0, scrollDistance)
    }

    override fun moveToPrevious() {
        recycler.smoothScrollBy(0, -scrollDistance)
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { moveToNext(); true }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> { moveToPrevious(); true }
            else -> false
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false
}
