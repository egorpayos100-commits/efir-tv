package plus.efir.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.data.Kind
import plus.efir.tv.player.LogoLoader
import plus.efir.tv.player.formatTimecode

class ChannelAdapter(
    private val onFocused: (Channel) -> Unit,
    private val onOpened: (Channel) -> Unit,
    private val onFlag: (Channel) -> Unit,
    private val isFavorite: (Channel) -> Boolean,
    /** Saved film position, or null when there is nothing to continue. */
    private val resumeOf: (Channel) -> Long?,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    private var list: RecyclerView? = null
    private var pendingFocus = -1
    private var pendingFlag = -1

    /** Row whose flag holds focus, -1 otherwise. The activity steers arrows with it. */
    var flagPosition = -1
        private set

    fun isFlagFocused() = flagPosition >= 0

    fun submit(next: List<Channel>) {
        items = next
        pendingFocus = -1
        pendingFlag = -1
        notifyDataSetChanged()
    }

    fun requestFocusAt(position: Int) {
        if (position !in items.indices) return
        val existing = list?.findViewHolderForAdapterPosition(position)
        if (existing != null) {
            existing.itemView.requestFocus()
        } else {
            pendingFocus = position
            list?.scrollToPosition(position)
        }
    }

    fun requestFlagFocusAt(position: Int) {
        if (position !in items.indices) return
        val existing = list?.findViewHolderForAdapterPosition(position) as? Holder
        if (existing != null) {
            existing.star.requestFocus()
        } else {
            pendingFlag = position
            list?.scrollToPosition(position)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
    }

    override fun onViewRecycled(holder: Holder) {
        holder.logo.tag = null
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        val position = holder.bindingAdapterPosition
        if (position == pendingFlag) {
            pendingFlag = -1
            holder.star.requestFocus()
        } else if (position == pendingFocus) {
            pendingFocus = -1
            holder.itemView.requestFocus()
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) =
        if (items[position].kind == Kind.Film) VIEW_FILM else VIEW_LIVE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(
            if (viewType == VIEW_FILM) R.layout.item_film else R.layout.item_channel,
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        /** Missing on poster rows, which have no channel number to show. */
        private val num: TextView? = view.findViewById(R.id.row_num)
        val logo: ImageView = view.findViewById(R.id.row_logo)
        private val name: TextView = view.findViewById(R.id.row_name)
        private val meta: TextView = view.findViewById(R.id.row_meta)
        val star: TextView = view.findViewById(R.id.row_star)
        private val mark: View = view.findViewById(R.id.row_mark)

        init {
            mark.alpha = 0f
            itemView.setOnFocusChangeListener { _, focused ->
                mark.animate().alpha(if (focused) 1f else 0f).setDuration(120).start()
                if (focused && bindingAdapterPosition >= 0) onFocused(items[bindingAdapterPosition])
            }
            itemView.setOnClickListener {
                if (bindingAdapterPosition >= 0) onOpened(items[bindingAdapterPosition])
            }
            star.setOnClickListener {
                if (bindingAdapterPosition >= 0) onFlag(items[bindingAdapterPosition])
            }
            star.setOnFocusChangeListener { _, focused ->
                if (focused) flagPosition = bindingAdapterPosition
                else if (flagPosition == bindingAdapterPosition) flagPosition = -1
            }
        }

        fun bind(channel: Channel) {
            num?.text = channel.number.toString()
            name.text = channel.name
            val favorite = isFavorite(channel)
            star.isSelected = favorite
            star.setText(if (favorite) R.string.favorite_on else R.string.favorite_off)
            val resume = resumeOf(channel)
            meta.text = when {
                resume != null -> "${channel.group} · с ${formatTimecode(resume)}"
                channel.sources.size > 1 -> "${channel.group} · ${channel.sources.size} источника"
                else -> channel.group
            }
            val tile = if (channel.kind == Kind.Film) channel.swatch(POSTER_W, POSTER_H)
            else channel.swatch(TILE_W, TILE_H)
            LogoLoader.into(logo, channel.logoUrl, tile)
        }
    }

    companion object {
        private const val VIEW_LIVE = 0
        private const val VIEW_FILM = 1
        private const val TILE_W = 160
        private const val TILE_H = 90
        private const val POSTER_W = 124
        private const val POSTER_H = 186
    }
}
