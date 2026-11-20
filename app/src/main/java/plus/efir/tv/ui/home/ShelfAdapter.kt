package plus.efir.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.player.LogoLoader
import plus.efir.tv.player.formatTimecode

/** One strip of the home screen: a name, and the cards under it. */
data class Shelf(val title: String?, val channels: List<Channel>, val posters: Boolean = false)

/**
 * The home screen as streaming apps draw it: shelves of cards stacked down the middle, each one
 * scrolling sideways, so a whole group is read in one pass instead of a column of rows.
 *
 * Every shelf owns its own horizontal list. Focus moves between the cards of a row with left and
 * right, and between the shelves with up and down; the arrows are steered here, not by the system,
 * because the box's focus search never worked sideways across a wall.
 */
class ShelfAdapter(
    private val onFocused: (shelf: Int, card: Int, channel: Channel) -> Unit,
    private val onOpened: (Channel) -> Unit,
    private val onFlag: (Channel) -> Unit,
    /** Saved film position, or null when there is nothing to continue. */
    private val resumeOf: (Channel) -> Long?,
    /** The channel that is really on air, so its card keeps the white mark. */
    private val playingKey: () -> String?,
    /** Greyed out: the last background check could not load this channel at all. */
    private val isDead: (Channel) -> Boolean = { false },
    /** What is on the channel right now; the card says it so the row is readable at a glance. */
    private val nowOf: (Channel) -> String? = { null },
    /** How far the programme on air has run, 0..1, or a negative number when nothing is on. */
    private val runOf: (Channel) -> Float = { -1f },
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var shelves: List<Shelf> = emptyList()
    private var host: RecyclerView? = null

    /** The card the cursor stands on: card steps need it, and so does up out of the first shelf. */
    private var focusShelf = -1
    private var focusCard = -1

    private var pendingShelf = -1
    private var pendingCard = -1

    /** The card that carries the white mark right now, so only a real zap repaints the rows. */
    private var shownPlaying: String? = null

    fun submit(next: List<Shelf>) {
        shelves = next
        shownPlaying = playingKey()
        focusShelf = -1
        focusCard = -1
        pendingShelf = -1
        pendingCard = -1
        notifyDataSetChanged()
    }

    /** Put the cursor on this very channel, wherever its shelf is. */
    fun focus(key: String?) {
        if (key != null) {
            shelves.forEachIndexed { index, shelf ->
                val at = shelf.channels.indexOfFirst { it.key == key }
                if (at >= 0) {
                    focusAt(index, at)
                    return
                }
            }
        }
        focusAt(0, 0)
    }

    /** The air moved to another channel: the two cards involved are the only ones repainted. */
    fun markPlaying(key: String?) {
        if (shownPlaying == key) return
        val was = shownPlaying
        shownPlaying = key
        repaintCard(was)
        repaintCard(key)
    }

    private fun repaintCard(key: String?) {
        if (key == null) return
        shelves.forEachIndexed { index, shelf ->
            val at = shelf.channels.indexOfFirst { it.key == key }
            if (at < 0) return@forEachIndexed
            val holder = host?.findViewHolderForAdapterPosition(index) as? Holder
                ?: return@forEachIndexed
            holder.repaintCard(at)
        }
    }

    /** The guide moved on: the cards say so. A rebind keeps the cursor, a resubmit would not. */
    fun refreshNow() {
        val list = host ?: return
        for (at in 0 until list.childCount) {
            (list.getChildViewHolder(list.getChildAt(at)) as? Holder)?.refresh()
        }
    }

    /** A minute moved every progress bar: only the tracks are repainted, the cards stay as they are. */
    fun refreshBars() {
        val list = host ?: return
        for (at in 0 until list.childCount) {
            (list.getChildViewHolder(list.getChildAt(at)) as? Holder)?.refreshBars()
        }
    }

    /** Down from the tabs: the first card of the first shelf. */
    fun focusFirst() = focusAt(0, 0)

    /** Up out of the top shelf: from there the cursor belongs to the tabs. */
    fun isAtFirstShelf() = focusShelf <= 0

    /** Left and right inside a row. Stops at the edges: the cursor never jumps sideways to a shelf. */
    fun stepHorizontal(delta: Int): Boolean {
        val shelf = shelves.getOrNull(focusShelf) ?: return false
        val target = focusCard + delta
        if (target !in shelf.channels.indices) return false
        val holder = host?.findViewHolderForAdapterPosition(focusShelf) as? Holder ?: return false
        // Only ask for the next card; focusShelf/focusCard follow the frame that really took the
        // cursor. Moving the state first let a fast key repeat run ahead of the layout, the cursor
        // out of the screen, and the tabs grabbed the focus that fell out of the detached card.
        holder.focusCard(target)
        return true
    }

    /** Up and down between shelves; false at the ends so the caller hands focus to the tabs. */
    fun stepVertical(delta: Int): Boolean {
        val target = focusShelf + delta
        if (target !in shelves.indices) return false
        val count = shelves[target].channels.size
        focusAt(target, focusCard.coerceIn(0, (count - 1).coerceAtLeast(0)))
        return true
    }

    private fun focusAt(shelf: Int, card: Int) {
        if (shelf !in shelves.indices) return
        val count = shelves[shelf].channels.size
        focusShelf = shelf
        focusCard = card.coerceIn(0, (count - 1).coerceAtLeast(0))
        val list = host ?: return
        val holder = list.findViewHolderForAdapterPosition(shelf) as? Holder
        if (holder != null) {
            list.smoothScrollToPosition(shelf)
            if (list.isInLayout || holder.row.isInLayout) {
                holder.row.post { holder.focusCard(focusCard) }
            } else {
                holder.focusCard(focusCard)
            }
        } else {
            pendingShelf = shelf
            pendingCard = focusCard
            list.smoothScrollToPosition(shelf)
            list.postDelayed({
                if (pendingShelf == shelf) {
                    val h = list.findViewHolderForAdapterPosition(shelf) as? Holder
                    if (h != null) {
                        pendingShelf = -1
                        val c = pendingCard
                        pendingCard = -1
                        h.focusCard(c)
                    }
                }
            }, 80L)
        }
    }

    private fun onCardFocused(shelf: Int, card: Int, channel: Channel) {
        focusShelf = shelf
        focusCard = card
        onFocused(shelf, card, channel)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        host = recyclerView
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        if (holder.bindingAdapterPosition == pendingShelf) {
            val card = pendingCard
            pendingShelf = -1
            pendingCard = -1
            holder.row.post { holder.focusCard(card) }
        }
    }

    override fun getItemCount() = shelves.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_shelf, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        shelves.getOrNull(position)?.let { holder.bind(it, position) }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.shelf_title)
        val row: RecyclerView = view.findViewById(R.id.shelf_row)
        private val adapter = RowAdapter(row)

        init {
            row.layoutManager = LinearLayoutManager(view.context, RecyclerView.HORIZONTAL, false)
            row.itemAnimator = null
            row.adapter = this.adapter
            row.nextFocusUpId = R.id.tabs
        }

        fun bind(shelf: Shelf, at: Int) {
            title.text = shelf.title.orEmpty()
            title.visibility = if (shelf.title.isNullOrBlank()) View.GONE else View.VISIBLE
            row.layoutParams = row.layoutParams.apply {
                height = dp(if (shelf.posters) ROW_POSTER_H else ROW_CARD_H)
            }
            adapter.submit(shelf.channels, shelf.posters, at)
        }

        fun focusCard(card: Int) = adapter.requestFocusAt(card)

        fun repaintCard(card: Int) = adapter.repaint(card)

        fun refresh() = adapter.refreshAll()

        fun refreshBars() = adapter.repaintBars()
    }

    /** The cards of one shelf. The row only ever holds one kind: channels or film posters. */
    private inner class RowAdapter(private val hostRow: RecyclerView) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Channel> = emptyList()
        private var posters = false
        private var shelf = -1
        private var pendingFocus = -1

        fun submit(next: List<Channel>, posterCards: Boolean, at: Int) {
            items = next
            posters = posterCards
            shelf = at
            pendingFocus = -1
            notifyDataSetChanged()
        }

        fun repaint(position: Int) {
            if (position in items.indices) notifyItemChanged(position)
        }

        fun refreshAll() = notifyDataSetChanged()

        fun repaintBars() {
            if (posters) return
            for (at in 0 until hostRow.childCount) {
                val holder = hostRow.getChildViewHolder(hostRow.getChildAt(at)) as? CardHolder ?: continue
                items.getOrNull(holder.bindingAdapterPosition)?.let(holder::updateBar)
            }
        }

        fun requestFocusAt(position: Int) {
            if (items.isEmpty()) return
            val target = position.coerceIn(0, items.lastIndex)
            val existing = hostRow.findViewHolderForAdapterPosition(target)
            if (existing != null) {
                existing.itemView.requestFocus()
            } else {
                pendingFocus = target
                hostRow.scrollToPosition(target)
                hostRow.post {
                    val holder = hostRow.findViewHolderForAdapterPosition(target)
                    if (holder != null) {
                        pendingFocus = -1
                        holder.itemView.requestFocus()
                    } else {
                        val first = hostRow.getChildAt(0)
                        if (first != null && !hostRow.hasFocus()) {
                            first.requestFocus()
                        }
                    }
                }
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            if (holder.bindingAdapterPosition == pendingFocus) {
                pendingFocus = -1
                holder.itemView.requestFocus()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == VIEW_POSTER) PosterHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            ) else CardHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_card, parent, false)
            )

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (posters) VIEW_POSTER else VIEW_CARD

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val channel = items.getOrNull(position) ?: return
            val playing = channel.key == playingKey()
            when (holder) {
                is CardHolder -> holder.bind(channel, playing)
                is PosterHolder -> holder.bind(channel, playing)
            }
        }

        private fun report(position: Int, channel: Channel) = onCardFocused(shelf, position, channel)

        inner class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val logo: ImageView = view.findViewById(R.id.card_logo)
            private val name: TextView = view.findViewById(R.id.card_name)
            private val now: TextView = view.findViewById(R.id.card_now)
            private val live: View = view.findViewById(R.id.card_live)
            private val run: View = view.findViewById(R.id.card_run)
            private val runFill: View = view.findViewById(R.id.card_run_fill)

            init {
                runFill.pivotX = 0f
                itemView.setOnFocusChangeListener { _, focused ->
                    if (!focused) {
                        name.alpha = 0.92f
                        return@setOnFocusChangeListener
                    }
                    val at = bindingAdapterPosition
                    if (at < 0) return@setOnFocusChangeListener
                    pendingFocus = -1
                    items.getOrNull(at)?.let { report(at, it) }
                    // The ring comes from the background; the word only brightens a touch.
                    name.alpha = 1f
                }
                itemView.setOnClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let(onOpened)
                }
                itemView.setOnLongClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let(onFlag)
                    true
                }
            }

            /** The air moves on between programme changes: the track alone catches up with it. */
            fun updateBar(channel: Channel) {
                val at = runOf(channel)
                run.visibility = if (at >= 0f) View.VISIBLE else View.GONE
                runFill.scaleX = at.coerceIn(0f, 1f)
            }

            fun bind(channel: Channel, playing: Boolean) {
                updateBar(channel)
                live.visibility = if (playing) View.VISIBLE else View.GONE
                // The air is drawn as a blue frame around the card, not only as the little strip.
                itemView.isSelected = playing
                itemView.alpha = if (isDead(channel)) DEAD_ALPHA else 1f
                val resume = resumeOf(channel)
                // The card says where the film stopped, if it was started; a channel card carries
                // what is on its air instead.
                name.text = if (resume != null) {
                    "${channel.displayName} · ${formatTimecode(resume)}"
                } else channel.displayName
                name.alpha = if (itemView.isFocused) 1f else 0.92f
                val air = nowOf(channel)
                // An empty line under the name reads as a broken screen; a channel whose guide has
                // nothing to say right now says so, and keeps the card the same height.
                now.text = air?.takeIf { it.isNotBlank() } ?: NO_PROGRAM
                now.visibility = View.VISIBLE
                LogoLoader.into(logo, channel.logoUrl, channel.swatch(LOGO_W, LOGO_H), LOGO_PX)
            }
        }

        inner class PosterHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val logo: ImageView = view.findViewById(R.id.row_logo)
            private val name: TextView = view.findViewById(R.id.row_name)
            private val meta: TextView = view.findViewById(R.id.row_meta)
            /** Poster cells no longer carry the star: the corner is the copy-quality flag. */
            private val star: TextView? = view.findViewById(R.id.row_star)
            private val mark: View = view.findViewById(R.id.row_mark)
            private val live: View = view.findViewById(R.id.row_live)
            private val score: TextView = view.findViewById(R.id.row_score)

            init {
                itemView.setOnFocusChangeListener { _, focused ->
                    if (!focused) {
                        itemView.scaleX = 1f
                        itemView.scaleY = 1f
                        return@setOnFocusChangeListener
                    }
                    val at = bindingAdapterPosition
                    if (at < 0) return@setOnFocusChangeListener
                    pendingFocus = -1
                    items.getOrNull(at)?.let { report(at, it) }
                    itemView.scaleX = POSTER_FOCUS_SCALE
                    itemView.scaleY = itemView.scaleX
                }
                itemView.setOnClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let(onOpened)
                }
                itemView.setOnLongClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let(onFlag)
                    true
                }
                itemView.layoutParams = itemView.layoutParams.apply {
                    width = dp(POSTER_W)
                    height = dp(POSTER_H)
                }
            }

            fun bind(channel: Channel, playing: Boolean) {
                name.text = channel.displayName
                meta.text = listOfNotNull(
                    channel.releaseYear?.toString(), channel.genre?.substringBefore(',')?.trim()
                ).joinToString(" · ")
                live.visibility = if (playing) View.VISIBLE else View.GONE
                mark.visibility = if (playing) View.VISIBLE else View.GONE
                itemView.isSelected = playing
                itemView.alpha = if (isDead(channel)) DEAD_ALPHA else 1f
                val rating = channel.rating
                score.visibility = if (rating != null && rating > 0) View.VISIBLE else View.GONE
                if (rating != null && rating > 0) {
                    score.text = String.format(java.util.Locale.US, "%.1f", rating)
                }
                star?.visibility = View.GONE
                LogoLoader.into(
                    logo, channel.logoUrl, channel.swatch(POSTER_TILE_W, POSTER_TILE_H), POSTER_PX
                )
            }
        }
    }

    private fun dp(value: Int) =
        (value * (host?.resources?.displayMetrics?.density ?: 2f)).toInt()

    companion object {
        private const val VIEW_CARD = 0
        private const val VIEW_POSTER = 1

        /** Row heights: the card plus its margins, so nothing is cut while the row scrolls. */
        private const val ROW_CARD_H = 96
        private const val ROW_POSTER_H = 208

        /** The logo plate of a channel card is 42dp square: 84px on the box, decoded at 130. */
        private const val LOGO_W = 100
        private const val LOGO_H = 100
        private const val LOGO_PX = 130

        private const val POSTER_W = 132
        private const val POSTER_H = 198
        private const val POSTER_TILE_W = 264
        private const val POSTER_TILE_H = 396
        private const val POSTER_PX = 300

        private const val DEAD_ALPHA = 0.42f
        private const val POSTER_FOCUS_SCALE = 1.04f
        private const val NO_PROGRAM = "Нет программы"
    }
}
