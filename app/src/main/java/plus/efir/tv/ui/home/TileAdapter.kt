package plus.efir.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.player.LogoLoader

/** What a tile shows: the programme on air now, its hours, and how far it has run. */
data class Tile(val title: String?, val from: String, val to: String, val fraction: Float)

/**
 * The guide as a table of tiles: several channels per row, each saying what plays on it right now.
 * A row list asks him to scroll channel by channel to learn anything; here the whole evening is on
 * one screen. Focus never zaps here — only ОК starts a channel, so walking the grid costs nothing.
 */
class TileAdapter(
    private val onOpened: (Channel) -> Unit,
    private val tileOf: (Channel) -> Tile,
    private val isDead: (Channel) -> Boolean = { false },
    /** The channel that is really on air: its tile keeps the blue frame of the air. */
    private val playingKey: () -> String? = { null },
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    private var grid: RecyclerView? = null
    private var pendingFocus = -1

    fun submit(next: List<Channel>) {
        items = next
        pendingFocus = -1
        notifyDataSetChanged()
    }

    fun requestFocusAt(position: Int) {
        if (position !in items.indices) return
        val existing = grid?.findViewHolderForAdapterPosition(position)
        if (existing != null) {
            existing.itemView.requestFocus()
        } else {
            pendingFocus = position
            grid?.scrollToPosition(position)
        }
    }

    /** Which tile holds the cursor now, or -1 while focus is not inside the table. */
    fun focusedPosition(): Int {
        val view = grid ?: return -1
        var child: View? = view.findFocus()
        while (child != null && child !== view) {
            if (child.parent === view) return view.getChildAdapterPosition(child)
            child = child.parent as? View
        }
        return -1
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        grid = recyclerView
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        if (holder.bindingAdapterPosition == pendingFocus) {
            pendingFocus = -1
            holder.itemView.requestFocus()
        }
    }

    /** Minutes move every bar: redrawing the whole grid by notification would drop the cursor. */
    fun repaint() {
        val view = grid ?: return
        for (i in 0 until view.childCount) {
            val holder = view.getChildViewHolder(view.getChildAt(i)) as? Holder ?: continue
            items.getOrNull(holder.bindingAdapterPosition)?.let(holder::bind)
        }
    }

    /** Repaint one tile: its programme changed, the rest of the table stays where it was. */
    fun updateRow(key: String) {
        val position = items.indexOfFirst { it.key == key }
        if (position >= 0) notifyItemChanged(position)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        items.getOrNull(position)?.let(holder::bind)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.tile_logo)
        private val name: TextView = view.findViewById(R.id.row_name)
        private val title: TextView = view.findViewById(R.id.row_meta)
        private val time: TextView = view.findViewById(R.id.tile_time)
        private val fill: View = view.findViewById(R.id.tile_fill)

        /** The minute tick rebinds every tile on screen; the logo and the name did not change. */
        private var logoFor: String? = null

        init {
            fill.pivotX = 0f
            itemView.setOnClickListener { row()?.let(onOpened) }
            itemView.setOnFocusChangeListener { _, focused -> light(focused) }
        }

        private fun color(@ColorRes id: Int) = ContextCompat.getColor(itemView.context, id)

        /**
         * The lit tile has to be obvious without looking: it grows, the frame comes from `tile_bg`,
         * and the name takes the accent colour while the programme brightens.
         */
        private fun light(focused: Boolean) {
            val scale = if (focused) FOCUS_SCALE else 1f
            itemView.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
            name.setTextColor(color(if (focused) R.color.accent else R.color.text_primary))
            title.setTextColor(color(if (focused) R.color.text_primary else R.color.text_muted))
            time.setTextColor(color(if (focused) R.color.accent else R.color.text_muted))
        }

        private fun row(): Channel? = items.getOrNull(bindingAdapterPosition)

        fun bind(channel: Channel) {
            val tile = tileOf(channel)
            itemView.isSelected = channel.key == playingKey()
            itemView.alpha = if (isDead(channel)) DEAD_ALPHA else 1f
            name.text = channel.name
            title.text = tile.title ?: itemView.context.getString(R.string.guide_silent)
            time.text = if (tile.title == null) channel.group else "${tile.from}–${tile.to}"
            fill.scaleX = tile.fraction
            if (logoFor != channel.key) {
                logoFor = channel.key
                LogoLoader.into(logo, channel.logoUrl, channel.swatch(LOGO_W, LOGO_H))
            }
            // A recycled holder is bound while it is already the focused one: no event will come.
            light(itemView.isFocused)
            if (tile.title == null) title.setTextColor(color(R.color.text_muted))
        }
    }

    companion object {
        /** Same dimming as the row list: a dead channel is still named, just clearly not watchable. */
        private const val DEAD_ALPHA = 0.42f

        /** Enough to read as a selection at 1080p, small enough not to sit on its neighbours. */
        private const val FOCUS_SCALE = 1.05f

        private const val LOGO_W = 140
        private const val LOGO_H = 80
    }
}
