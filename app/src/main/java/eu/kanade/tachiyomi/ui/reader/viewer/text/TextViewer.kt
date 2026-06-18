package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.hasMissingChapters
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.ThemeUtil
import kotlin.math.min
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import yokai.presentation.reader.ChapterTransition as ChapterTransitionContent
import yokai.presentation.theme.YokaiTheme

/** Continuous (scroll) Compose viewer for text/novel chapters. */
class TextViewer(val activity: ReaderActivity) : BaseViewer {

    private val scope = MainScope()
    val downloadManager: DownloadManager by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3f / 4f

    private var items by mutableStateOf<List<TextUi>>(emptyList())
    private val theme = mutableIntStateOf(preferences.readerTheme().get())
    private var listState: LazyListState? = null

    private var currentChapter: ReaderChapter? = null
    private var prevChapter: ReaderChapter? = null
    private var currentPage: ReaderPage? = null
    private var scrolledToInitial = false
    private var pendingScroll: Int? = null
    private var container: ViewGroup? = null

    private val composeView = ComposeView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        isFocusable = true
        isFocusableInTouchMode = true
        // viewer_container blocks descendant focus; Compose selection needs focus to show its toolbar.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                container = (v.parent as? ViewGroup)?.apply {
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        setContent {
            val state = rememberLazyListState()
            LaunchedEffect(state) {
                listState = state
                pendingScroll?.let { state.scrollToItem(it); pendingScroll = null }
                snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .collect(::onItemVisible)
            }
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex }.collect(::onTopVisible)
            }
            LaunchedEffect(state) {
                snapshotFlow { state.isScrollInProgress }.collect { if (it) activity.hideMenu() }
            }
            YokaiTheme {
                TextReader(
                    items = items,
                    theme = theme.intValue,
                    listState = state,
                    manga = activity.viewModel.manga,
                    downloadManager = downloadManager,
                    onTap = { activity.toggleMenu() },
                )
            }
        }
    }

    init {
        preferences.readerTheme().changesIn(scope) { theme.intValue = it }
    }

    override fun getView(): View = composeView

    override fun destroy() {
        container?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        scope.cancel()
    }

    override fun setChapters(chapters: ViewerChapters) {
        val force = preferences.alwaysShowChapterTransition().get()
        items = buildItems(chapters, force)
        if (!scrolledToInitial) {
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            scrolledToInitial = true
        }
    }

    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        val index = items.indexOfFirst { it is TextUi.Title && it.page == page }
        if (index != -1) {
            val state = listState
            if (state != null) scope.launch { state.scrollToItem(index) } else pendingScroll = index
            onPageChanged(page)
        }
    }

    override fun moveToNext() {
        scope.launch { listState?.animateScrollBy(scrollDistance) }
    }

    override fun moveToPrevious() {
        scope.launch { listState?.animateScrollBy(-scrollDistance) }
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

    private fun onItemVisible(index: Int) {
        when (val item = items.getOrNull(index)) {
            is TextUi.Title -> onPageChanged(item.page)
            is TextUi.Block -> onPageChanged(item.page)
            is TextUi.Transition -> item.transition.to?.let { activity.requestPreloadChapter(it) }
            null -> Unit
        }
    }

    private fun onTopVisible(index: Int) {
        val item = items.getOrNull(index)
        when {
            item is TextUi.Transition && item.transition is ChapterTransition.Prev ->
                item.transition.to?.let { activity.requestPreloadChapter(it) }
            index <= 1 -> prevChapter?.let { activity.requestPreloadChapter(it) }
        }
    }

    private fun onPageChanged(page: ReaderPage) {
        if (currentPage == page) return
        currentPage = page
        activity.onPageSelected(page, false)
        if (page.chapter == currentChapter) {
            val nextChapter = (items.lastOrNull() as? TextUi.Transition)?.transition?.to
                ?: (items.lastOrNull() as? TextUi.Block)?.page?.chapter
            if (nextChapter != null) activity.requestPreloadChapter(nextChapter)
        }
    }

    private fun buildItems(chapters: ViewerChapters, force: Boolean): List<TextUi> {
        val out = mutableListOf<TextUi>()
        val prevMissing = hasMissingChapters(chapters.currChapter, chapters.prevChapter)
        val nextMissing = hasMissingChapters(chapters.nextChapter, chapters.currChapter)

        chapters.prevChapter?.pages?.takeLast(1)?.forEach { out += chapterItems(it) }
        if (prevMissing || force || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            out += TextUi.Transition(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }
        chapters.currChapter.pages?.forEach { out += chapterItems(it) }
        currentChapter = chapters.currChapter
        prevChapter = chapters.prevChapter
        if (nextMissing || force || chapters.nextChapter?.state !is ReaderChapter.State.Loaded) {
            out += TextUi.Transition(ChapterTransition.Next(chapters.currChapter, chapters.nextChapter))
        }
        chapters.nextChapter?.pages?.take(1)?.forEach { out += chapterItems(it) }
        return out
    }

    private fun chapterItems(page: ReaderPage): List<TextUi> =
        listOf(TextUi.Title(page)) + page.blocks.orEmpty().mapIndexed { i, block -> TextUi.Block(page, i, block) }
}

private sealed interface TextUi {
    data class Title(val page: ReaderPage) : TextUi
    data class Block(val page: ReaderPage, val index: Int, val block: TextBlock) : TextUi
    data class Transition(val transition: ChapterTransition) : TextUi
}

private fun TextUi.key(): String = when (this) {
    is TextUi.Title -> "t${page.chapter.chapter.id}"
    is TextUi.Block -> "b${page.chapter.chapter.id}_$index"
    is TextUi.Transition -> "x${transition.from.chapter.id}_${transition.to?.chapter?.id}_${transition::class.simpleName}"
}

@Composable
private fun TextReader(
    items: List<TextUi>,
    theme: Int,
    listState: LazyListState,
    manga: Manga?,
    downloadManager: DownloadManager,
    onTap: () -> Unit,
) {
    val background = Color(ThemeUtil.readerBackgroundColor(theme, MaterialTheme.colorScheme.background.toArgb()))
    val contentColor = ThemeUtil.readerContentColor(theme, MaterialTheme.colorScheme.onBackground)
    val toolbar = remember { TextSelectionToolbar() }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        CompositionLocalProvider(LocalTextToolbar provides toolbar) {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null && !up.isConsumed) onTap()
                            }
                        },
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(items, key = { it.key() }, contentType = { it::class }) { item ->
                        when (item) {
                            is TextUi.Title -> Text(
                                text = item.page.chapter.chapter.name,
                                color = contentColor,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            )
                            is TextUi.Block -> BlockContent(item.block, contentColor)
                            is TextUi.Transition -> TransitionContent(item.transition, theme, manga, downloadManager)
                        }
                    }
                }
            }
        }
        SelectionCopyBar(toolbar)
    }
}

private class TextSelectionToolbar : TextToolbar {
    private var onCopy: (() -> Unit)? = null
    private var statusState by mutableStateOf(TextToolbarStatus.Hidden)
    override val status: TextToolbarStatus get() = statusState

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        onCopy = onCopyRequested
        statusState = TextToolbarStatus.Shown
    }

    override fun hide() {
        statusState = TextToolbarStatus.Hidden
        onCopy = null
    }

    fun copy() {
        onCopy?.invoke()
        hide()
    }
}

@Composable
private fun BoxScope.SelectionCopyBar(toolbar: TextSelectionToolbar) {
    if (toolbar.status != TextToolbarStatus.Shown) return
    Surface(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 6.dp,
    ) {
        TextButton(onClick = toolbar::copy) {
            Text(stringResource(android.R.string.copy))
        }
    }
}

@Composable
private fun BlockContent(block: TextBlock, color: Color) {
    when (block) {
        is TextBlock.Paragraph -> Text(
            text = remember(block.html) { AnnotatedString.fromHtml(block.html) },
            color = color,
            fontSize = 18.sp,
            lineHeight = 27.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        is TextBlock.Heading -> Text(
            text = remember(block.html) { AnnotatedString.fromHtml(block.html) },
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        TextBlock.Rule -> HorizontalDivider(
            color = color.copy(alpha = 0.3f),
            modifier = Modifier.padding(vertical = 16.dp),
        )
        is TextBlock.Image -> Unit
    }
}

@Composable
private fun TransitionContent(
    transition: ChapterTransition,
    theme: Int,
    manga: Manga?,
    downloadManager: DownloadManager,
) {
    manga ?: return
    CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.bodySmall,
        LocalContentColor provides ThemeUtil.readerContentColor(theme, MaterialTheme.colorScheme.onBackground),
    ) {
        ChapterTransitionContent(
            manga = manga,
            transition = transition,
            currChapterDownloaded = transition.from.pageLoader?.isLocal == true,
            goingToChapterDownloaded = manga.isLocal() ||
                transition.to?.chapter?.let { downloadManager.isChapterDownloaded(it, manga, skipCache = true) } ?: false,
        )
    }
}
