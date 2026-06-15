package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTransitionView

class TextTransitionHolder(
    private val view: ReaderTransitionView,
    private val viewer: TextViewer,
) : RecyclerView.ViewHolder(view) {

    init {
        view.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    fun bind(transition: ChapterTransition) {
        view.bind(viewer.readerTheme, transition, viewer.downloadManager, viewer.activity.viewModel.manga)
        transition.to?.let { viewer.activity.requestPreloadChapter(it) }
    }
}
