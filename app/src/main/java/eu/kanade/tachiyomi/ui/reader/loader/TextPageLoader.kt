package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.text.TextChapterParser

class TextPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
) : PageLoader() {

    override val isLocal: Boolean = false

    override suspend fun getPages(): List<ReaderPage> {
        return source.getPageList(chapter.chapter).mapIndexed { index, page ->
            ReaderPage(index, page.url, page.imageUrl).apply {
                blocks = TextChapterParser.parse(page.html.orEmpty())
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }
}
