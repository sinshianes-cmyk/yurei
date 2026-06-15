package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.graphics.Color
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.ThemeUtil
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor

/**
 * Renders one text chapter ([ReaderPage.html]) into a TextView so chapters size to their content
 * and scroll continuously in the recycler.
 */
class TextPageHolder(
    private val textView: AppCompatTextView,
    private val viewer: TextViewer,
) : RecyclerView.ViewHolder(textView) {

    init {
        textView.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val padding = 16.dpToPx
        textView.setPadding(padding, padding, padding, padding)
        textView.textSize = 18f
        textView.setLineSpacing(0f, 1.4f)
        textView.setTextIsSelectable(true)
    }

    fun bind(page: ReaderPage) {
        val background = ThemeUtil.readerBackgroundColor(
            viewer.readerTheme,
            textView.context.getResourceColor(R.attr.background),
        )
        val color = if (ColorUtils.calculateLuminance(background) < 0.5) Color.WHITE else Color.BLACK
        textView.setBackgroundColor(background)
        textView.setTextColor(color)
        textView.setLinkTextColor(color)

        val title = page.chapter.chapter.name.htmlEscape()
        textView.text = HtmlCompat.fromHtml("<h2>$title</h2>${page.html.orEmpty()}", HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    fun recycle() {
        textView.text = null
    }

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
