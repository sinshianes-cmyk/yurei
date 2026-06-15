package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTransitionView
import eu.kanade.tachiyomi.ui.reader.viewer.hasMissingChapters

class TextAdapter(private val viewer: TextViewer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<Any> = emptyList()
        private set

    var currentChapter: ReaderChapter? = null

    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val newItems = mutableListOf<Any>()

        val prevHasMissingChapters = hasMissingChapters(chapters.currChapter, chapters.prevChapter)
        val nextHasMissingChapters = hasMissingChapters(chapters.nextChapter, chapters.currChapter)

        chapters.prevChapter?.pages?.let { newItems.addAll(it.takeLast(1)) }

        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        chapters.currChapter.pages?.let { newItems.addAll(it) }
        currentChapter = chapters.currChapter

        if (nextHasMissingChapters || forceTransition || chapters.nextChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Next(chapters.currChapter, chapters.nextChapter))
        }

        chapters.nextChapter?.pages?.let { newItems.addAll(it.take(1)) }

        val result = DiffUtil.calculateDiff(Callback(items, newItems))
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ReaderPage -> PAGE_VIEW
        is ChapterTransition -> TRANSITION_VIEW
        else -> error("Unknown view type")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        PAGE_VIEW -> TextPageHolder(AppCompatTextView(parent.context), viewer)
        TRANSITION_VIEW -> TextTransitionHolder(ReaderTransitionView(parent.context), viewer)
        else -> error("Unknown view type")
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TextPageHolder -> holder.bind(items[position] as ReaderPage)
            is TextTransitionHolder -> holder.bind(items[position] as ChapterTransition)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TextPageHolder) holder.recycle()
    }

    private class Callback(
        private val oldItems: List<Any>,
        private val newItems: List<Any>,
    ) : DiffUtil.Callback() {
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldItems[oldItemPosition] == newItems[newItemPosition]

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = true
        override fun getOldListSize(): Int = oldItems.size
        override fun getNewListSize(): Int = newItems.size
    }

    private companion object {
        const val PAGE_VIEW = 0
        const val TRANSITION_VIEW = 1
    }
}
