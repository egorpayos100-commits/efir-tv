package plus.efir.tv.ui.home

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.data.Kind
import plus.efir.tv.player.LogoLoader
import plus.efir.tv.player.formatTimecode

/**
 * Rows of one list: live channels as plain rows, films as vertical posters, and — on the cinema
 * tab — a banner of the newest release above them. The banner is the first item of the grid and
 * spans every column, so it scrolls away with the wall instead of eating a fixed band of screen.
 */
class ChannelAdapter(
    private val onFocused: (Channel) -> Unit,
    private val onOpened: (Channel) -> Unit,
    private val onFlag: (Channel) -> Unit,
    private val isFavorite: (Channel) -> Boolean,
    /** Saved film position, or null when there is nothing to continue. */
    private val resumeOf: (Channel) -> Long?,
    /** Warning read off the saved address, or null when the row needs no note. */
    private val noteOf: (Channel) -> String?,
    /** Greyed out: the last background check could not load this channel at all. */
    private val isDead: (Channel) -> Boolean = { false },
    /** What is on this channel right now, already worded for the row; null when the guide is silent. */
    private val programmeOf: (Channel) -> String? = { null },
    /** The three buttons of the cinema banner: only the main list supplies them. */
    private val onWatch: ((Channel) -> Unit)? = null,
    private val onTrailer: ((Channel) -> Unit)? = null,
    private val onMore: ((Channel) -> Unit)? = null,
    private val metaOf: (Channel) -> String = { "" },
    private val hasTrailer: (Channel) -> Boolean = { false },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<Channel> = emptyList()

    /** The banner over the poster wall; null when this list is not the cinema. */
    private var billboard: Channel? = null

    /** Which banner of how many is on: the dots under it count them out. */
    private var billAt = 0
    private var billTotal = 0

    /** The one group a shelf is made of, or null when its rows come from several of them. */
    private var shelfGroup: String? = null
    private var list: RecyclerView? = null
    private var pendingFocus = -1
    private var pendingFlag = -1
    /** The banner was asked for while it was scrolled off; it takes the cursor when it lands. */
    private var pendingBill = false

    /** Height of one poster cell, so a 2:3 card stays 2:3 at any column count. */
    var posterHeight = 0
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Row whose flag holds focus, -1 otherwise. The activity steers arrows with it. */
    var flagPosition = -1
        private set

    /**
     * The row that is really running. Only OK changes it, so walking the list never moves the lit
     * strip: after zapping through channels he can see at a glance which one is still on air, and
     * which film he left playing.
     */
    var playingKey: String? = null
        set(value) {
            if (field == value) return
            val was = field
            field = value
            was?.let(::updateRow)
            value?.let(::updateRow)
        }

    private fun head() = if (billboard != null) 1 else 0

    fun isFlagFocused() = flagPosition >= 0

    fun count() = items.size

    fun hasBill() = billboard != null

    /** Adapter position of a billboard: it is the first cell of the grid, spanning every column. */
    fun isBill(position: Int) = billboard != null && position == 0

    /**
     * The cell of this very list that holds the cursor, or -1 when the cursor is elsewhere.
     * Only a direct child may be asked for its position: a card inside a shelf row sits in a nested
     * list, and handing that view to the outer RecyclerView made the box throw and drop the screen.
     */
    private fun cellUnderCursor(): Int {
        val rv = list ?: return -1
        var view: View? = rv.findFocus()
        while (view != null && view !== rv) {
            if (view.parent === rv) return rv.getChildAdapterPosition(view)
            view = view.parent as? View
        }
        return -1
    }

    /** Which item holds the cursor: the grid's own focus search does not move sideways on the box. */
    fun focusedPosition(): Int {
        val at = cellUnderCursor()
        if (at < 0) return -1
        return if (isBill(at)) -2 else at - head()
    }

    fun billHasFocus() = cellUnderCursor() == 0 && isBill(0)

    fun requestBillFocus(): Boolean {
        if (!hasBill()) return false
        val rv = list ?: return false
        pendingBill = true
        pendingFocus = -1
        pendingFlag = -1
        // Climbing the wall keeps the row under the cursor aligned to the top, so the banner can
        // be attached yet outside the screen — asking such a holder for focus moves nothing and
        // leaves him standing on the first row of posters with no banner in sight. The list is put
        // back to its very start instead, and the banner takes the cursor once it is really there.
        rv.stopScroll()
        rv.scrollToPosition(0)
        if (takeBillFocus()) return true
        rv.post { takeBillFocus() }
        return true
    }

    /** The banner takes the cursor only while it is really on the screen. */
    private fun takeBillFocus(): Boolean {
        if (!pendingBill) return false
        val rv = list ?: return false
        val holder = rv.findViewHolderForAdapterPosition(0) as? BillHolder ?: return false
        if (!holder.itemView.isShown || holder.itemView.height == 0) return false
        pendingBill = false
        holder.buttons.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
        return true
    }

    /** Left and right between the banner's buttons; false at the ends. */
    fun billButton(delta: Int): Boolean {
        val holder = list?.findViewHolderForAdapterPosition(0) as? BillHolder ?: return false
        val shown = holder.buttons.filter { it.visibility == View.VISIBLE }
        val at = shown.indexOfFirst { it.isFocused }
        if (at < 0) return false
        val target = at + delta
        if (target !in shown.indices) return false
        shown[target].requestFocus()
        return true
    }

    /**
     * Where the next arrow starts from. While a row glides in, focus still sits on the card that is
     * leaving the screen, so the pending target is the honest answer and a held key keeps moving.
     */
    fun steeringPosition(): Int = if (pendingFocus >= 0) pendingFocus else focusedPosition()

    fun submit(next: List<Channel>, bill: Channel? = null, at: Int = 0, total: Int = 0) {
        items = next
        billboard = bill
        billAt = at
        billTotal = total
        shelfGroup = next.map { it.group }.distinct().singleOrNull()
        pendingFocus = -1
        pendingFlag = -1
        pendingBill = false
        // A recycled holder keeps reporting its old position, so the activity must not steer with it.
        flagPosition = -1
        notifyDataSetChanged()
    }

    /** Swap the banner under the cursor without touching the wall; the rotation uses this. */
    fun setBillboard(film: Channel?, at: Int = 0, total: Int = 0) {
        if (billboard?.key == film?.key) {
            if (billAt == at && billTotal == total) return
            billAt = at
            billTotal = total
            if (billboard != null) notifyItemChanged(0)
            return
        }
        val had = billboard != null
        billboard = film
        billAt = at
        billTotal = total
        when {
            had && film != null -> notifyItemChanged(0)
            else -> notifyDataSetChanged()
        }
    }

    /** Repaint the note column; the rows themselves did not change. */
    fun refreshStatuses() = notifyDataSetChanged()

    /** Repaint a single row: a whole-table redraw would drop the cursor. */
    fun updateRow(key: String) {
        val position = items.indexOfFirst { it.key == key }
        if (position >= 0) notifyItemChanged(position + head())
    }

    fun requestFocusAt(position: Int) {
        if (position !in items.indices) return
        val rv = list ?: return
        val at = position + head()
        pendingBill = false
        val existing = rv.findViewHolderForAdapterPosition(at)
        if (existing != null) {
            existing.itemView.requestFocus()
        } else {
            pendingFocus = position
            rv.scrollToPosition(at)
        }
    }

    /**
     * One arrow step across a poster wall. The list is jumped straight to the target card and takes
     * focus there: the custom glide that used to run for every press was the very thing that made
     * the wall stutter on the box, so the movement is left to the list itself.
     */
    fun stepTo(position: Int) = requestFocusAt(position)

    fun requestFlagFocusAt(position: Int) {
        if (position !in items.indices) return
        val existing = list?.findViewHolderForAdapterPosition(position + head()) as? Holder
        if (existing != null) {
            existing.star?.requestFocus()
        } else {
            pendingFlag = position
            list?.scrollToPosition(position + head())
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
        // The banner can be attached and still be off the screen; then only the scroll that brings
        // it back can hand it the cursor, and this is where that is noticed.
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (pendingBill) takeBillFocus()
            }
        })
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is Holder) holder.logo.tag = null
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        val position = holder.bindingAdapterPosition
        if (pendingBill && position == 0 && holder is BillHolder) {
            pendingBill = false
            pendingFocus = -1
            holder.buttons.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
        } else if (position == pendingFlag + head() && holder is Holder) {
            pendingFlag = -1
            holder.star?.requestFocus()
        } else if (position - head() == pendingFocus) {
            // No waiting for a glide to settle: a cell that arrives and is not taken leaves the
            // request pending for good, and every further press then only scrolled the wall.
            pendingFocus = -1
            holder.itemView.requestFocus()
        }
    }

    override fun getItemCount() = items.size + head()

    override fun getItemViewType(position: Int): Int {
        if (isBill(position)) return VIEW_BILL
        val channel = items[position - head()]
        // Playlist rows are commands, not films: they were drawn as poster cards with a giant
        // monogram letter, which made the settings pane look like a shelf of broken movies.
        return if (channel.kind == Kind.Film && !channel.isAction) VIEW_POSTER else VIEW_LIVE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_BILL -> BillHolder(inflater.inflate(R.layout.film_hero, parent, false))
            VIEW_POSTER -> Holder(inflater.inflate(R.layout.item_poster, parent, false))
            else -> Holder(inflater.inflate(R.layout.item_channel, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is BillHolder && isBill(position)) {
            billboard?.let(holder::bind)
        } else if (holder is Holder) {
            items.getOrNull(position - head())?.let(holder::bind)
        }
    }

    /** The banner of the newest release: a wide still and the three things to do with it. */
    inner class BillHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val backdrop: ImageView = view.findViewById(R.id.hero_backdrop)
        private val badge: TextView = view.findViewById(R.id.hero_badge)
        private val title: TextView = view.findViewById(R.id.hero_title)
        private val meta: TextView = view.findViewById(R.id.hero_meta)
        private val watch: TextView = view.findViewById(R.id.hero_watch)
        private val trailer: TextView = view.findViewById(R.id.hero_trailer)
        private val more: TextView = view.findViewById(R.id.hero_more)
        private val dots: LinearLayout = view.findViewById(R.id.hero_dots)

        val buttons: List<TextView> get() = listOf(watch, trailer, more)

        init {
            watch.setOnClickListener {
                billboard?.let { film -> onWatch?.invoke(film) }
            }
            trailer.setOnClickListener {
                billboard?.let { film -> onTrailer?.invoke(film) }
            }
            more.setOnClickListener {
                billboard?.let { film -> onMore?.invoke(film) }
            }
        }

        fun bind(film: Channel) {
            badge.visibility = View.VISIBLE
            title.text = film.displayName
            meta.text = metaOf(film)
            watch.setText(if (resumeOf(film) != null) R.string.film_continue else R.string.film_watch)
            trailer.visibility = if (hasTrailer(film)) View.VISIBLE else View.GONE
            LogoLoader.into(backdrop, film.backdropUrl ?: film.logoUrl, film.swatch(BILL_W, BILL_H), BILL_PX)
            paintDots()
        }

        /** One dot per banner, filled for the one in front: the row below is longer than one film. */
        private fun paintDots() {
            dots.visibility = if (billTotal > 1) View.VISIBLE else View.GONE
            if (billTotal <= 1) return
            if (dots.childCount != billTotal) {
                dots.removeAllViews()
                val side = dots.resources.displayMetrics.density.let { (6f * it).toInt() }
                val gap = dots.resources.displayMetrics.density.let { (4f * it).toInt() }
                repeat(billTotal) {
                    val dot = View(dots.context)
                    dot.layoutParams = LinearLayout.LayoutParams(side, side).apply {
                        marginStart = gap
                        marginEnd = gap
                    }
                    dots.addView(dot)
                }
            }
            for (i in 0 until dots.childCount) {
                dots.getChildAt(i).setBackgroundResource(
                    if (i == billAt) R.drawable.banner_dot_on else R.drawable.banner_dot
                )
            }
        }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        /** Missing on poster rows, which have no channel number to show. */
        private val num: TextView? = view.findViewById(R.id.row_num)
        val logo: ImageView = view.findViewById(R.id.row_logo)
        private val name: TextView = view.findViewById(R.id.row_name)
        private val meta: TextView = view.findViewById(R.id.row_meta)
        /** Missing on poster cells: the corner now holds the copy-quality flag. */
        val star: TextView? = view.findViewById(R.id.row_star)
        /** Only on poster cells: the Kinopoisk rating, in the same corner as on the phone. */
        private val score: TextView? = view.findViewById(R.id.row_score)
        /** Only on poster cells: «TS», while the shelf holds a copy that is not HD yet. */
        private val quality: TextView? = view.findViewById(R.id.row_quality)
        private val mark: View = view.findViewById(R.id.row_mark)
        private val live: View = view.findViewById(R.id.row_live)
        /** Present only on a film card: the caption sits on the picture. */
        private val caption: View? = view.findViewById(R.id.row_caption)

        init {
            // The arrow is not a decoration for the cursor: it says «this row is on air». It used to
            // flash up under the cursor, so every row he walked past claimed to be the running one.
            itemView.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    // The cursor landed, whatever asked for it: no stale request may keep steering.
                    pendingFocus = -1
                    row()?.let(onFocused)
                }
                light(focused)
            }
            itemView.setOnClickListener { row()?.let(onOpened) }
            star?.setOnClickListener { row()?.let(onFlag) }
            star?.setOnFocusChangeListener { _, focused ->
                if (focused) flagPosition = bindingAdapterPosition - head()
                else if (flagPosition == bindingAdapterPosition - head()) flagPosition = -1
            }
            // Long OK on a row is the star: the remote has no spare button for favourites.
            itemView.setOnLongClickListener {
                row()?.let(onFlag)
                true
            }
        }

        /** A holder recycled after the list shrank still reports its old position, so index blindly and it crashes. */
        private fun row(): Channel? = items.getOrNull(bindingAdapterPosition - head())

        private fun color(@ColorRes id: Int) = ContextCompat.getColor(itemView.context, id)

        /**
         * The lit film card grows a little, the way the phone's cards lift under the finger; the
         * border comes from `film_card_bg`. A live row keeps its own strip and must not move.
         */
        private fun light(focused: Boolean) {
            if (caption == null) return
            // No animator here: one per bind of every card on the wall was a frame's worth of work
            // for a lift nobody can see while the cursor runs across the row.
            val scale = if (focused) POSTER_FOCUS_SCALE else 1f
            itemView.scaleX = scale
            itemView.scaleY = scale
        }

        fun bind(channel: Channel) {
            num?.text = channel.number.toString()
            val film = channel.kind == Kind.Film
            val year = if (film) channel.releaseYear else null
            name.text = channel.displayName
            // The white strip and the bold name mean «this one is running» whatever the cursor does;
            // the card also takes the blue frame of the air, the same one the shelves draw.
            val playing = channel.key == playingKey
            live.visibility = if (playing) View.VISIBLE else View.GONE
            mark.visibility = if (playing) View.VISIBLE else View.GONE
            itemView.isSelected = playing
            name.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            name.setTextColor(color(if (playing) R.color.on_air else R.color.text_primary))
            itemView.alpha = if (!channel.isAction && isDead(channel)) DEAD_ALPHA else 1f
            val favorite = isFavorite(channel)
            star?.apply {
                isSelected = favorite
                setText(if (favorite) R.string.favorite_on else R.string.favorite_off)
            }
            val resume = resumeOf(channel)
            val onAir = programmeOf(channel)
            // Inside a shelf of one group the word above the list already says it, and the row has
            // no room for it: «Познавательные» used to break in half right in front of him.
            val group = channel.group.takeIf { channel.isAction || it != shelfGroup }
            val tail = when {
                film && channel.sources.isEmpty() -> "нет файла в библиотеке"
                resume != null -> "с ${formatTimecode(resume)}"
                film && year != null -> year.toString()
                channel.sources.size > 1 -> "${channel.sources.size} источника"
                else -> null
            }
            val filmScore = channel.rating
            score?.apply {
                val show = channel.kind == Kind.Film && filmScore != null && filmScore > 0
                visibility = if (show) View.VISIBLE else View.GONE
                if (show) text = String.format(java.util.Locale.US, "%.1f", filmScore)
            }
            quality?.apply {
                val copy = if (film) channel.quality?.trim().orEmpty() else ""
                visibility = if (copy.isEmpty()) View.GONE else View.VISIBLE
                text = copy
            }
            // The programme beats the category: he reads the list to decide what to watch.
            val line = if (film) channel.genre?.substringBefore(',')?.trim() else onAir ?: group
            val base = listOfNotNull(line, tail).joinToString(" · ")
            meta.text = noteOf(channel)?.let { if (base.isEmpty()) it else "$base · $it" } ?: base
            if (film) {
                // A poster is 2:3 whatever the column count is: the cell is as tall as it is wide
                // times one and a half, so six in a row still read as film posters, not as cards.
                if (posterHeight > 0 && itemView.layoutParams?.height != posterHeight) {
                    itemView.layoutParams = itemView.layoutParams.apply { height = posterHeight }
                }
                LogoLoader.into(logo, channel.logoUrl, channel.swatch(POSTER_W, POSTER_H), POSTER_PX)
            } else {
                LogoLoader.into(logo, channel.logoUrl, channel.swatch(TILE_W, TILE_H), LOGO_PX)
            }
            // A recycled card is bound while it already holds the cursor: no focus event will come.
            light(itemView.isFocused)
        }
    }

    companion object {
        private const val VIEW_LIVE = 0
        private const val VIEW_POSTER = 1
        private const val VIEW_BILL = 2
        private const val TILE_W = 160
        private const val TILE_H = 90

        /**
         * Monogram for a poster card that brought no artwork. It is drawn at the size the card
         * really is on the box: a 300x450 plate for it was half a megabyte of gradient per film,
         * redrawn on the UI thread whenever the cache dropped it.
         */
        private const val POSTER_W = 220
        private const val POSTER_H = 330

        private const val BILL_W = 1280
        private const val BILL_H = 400
        private const val BILL_PX = 960

        /** A lit film card lifts a little, the way a phone card does under the finger. */
        private const val POSTER_FOCUS_SCALE = 1.04f

        /** The card is 110dp — 220px on this box: decoding at 224px keeps a 600x900 file small. */
        private const val POSTER_PX = 224
        private const val LOGO_PX = 192

        /** A row the last check could not load: still visible, but clearly not working. */
        private const val DEAD_ALPHA = 0.42f
    }
}
