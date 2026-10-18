package plus.efir.tv.ui.home

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import plus.efir.tv.EfirApp
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.ui.watch.WatchActivity

class HomeActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_frame, BrowseFragment())
                .commit()
        }
    }

    class BrowseFragment : BrowseSupportFragment() {

        private val presenter by lazy { CardPresenter() }

        override fun onActivityCreated(savedInstanceState: Bundle?) {
            super.onActivityCreated(savedInstanceState)
            title = getString(R.string.app_name)
            headersState = HEADERS_ENABLED
            isHeadersTransitionOnBackEnabled = true
            badgeDrawable = null
            setupRows()
            onItemViewClickedListener = object : OnItemViewClickedListener {
                override fun onItemClicked(
                    itemViewHolder: Presenter.ViewHolder?,
                    item: Any?,
                    rowViewHolder: RowPresenter.ViewHolder?,
                    row: Row?
                ) {
                    val channel = item as? Channel ?: return
                    requireContext().prefs().edit().putString(LAST_KEY, channel.key).apply()
                    WatchActivity.start(requireContext(), channel.key)
                }
            }
        }

        private fun setupRows() {
            val repo = (requireContext().applicationContext as EfirApp).repository
            val all = repo.channels()
            val rows = ArrayObjectAdapter(ListRowPresenter())

            val lastKey = requireContext().prefs().getString(LAST_KEY, null)
            val last = all.firstOrNull { it.key == lastKey }
            if (last != null) rows.add(ListRow(HeaderItem(0L, "Продолжить смотреть"), adapterOf(listOf(last))))

            repo.byGroup().forEachIndexed { index, (group, channels) ->
                rows.add(ListRow(HeaderItem((index + 1).toLong(), group), adapterOf(channels)))
            }

            if (all.isEmpty()) {
                rows.add(ListRow(HeaderItem(99L, "Нет каналов"), adapterOf(emptyList())))
            }
            adapter = rows
        }

        private fun adapterOf(channels: List<Channel>): ArrayObjectAdapter {
            val adapter = ArrayObjectAdapter(presenter)
            channels.forEach { adapter.add(it) }
            return adapter
        }

        private fun Context.prefs() = getSharedPreferences("efir", Context.MODE_PRIVATE)

        companion object {
            const val LAST_KEY = "last_channel"
        }
    }
}
