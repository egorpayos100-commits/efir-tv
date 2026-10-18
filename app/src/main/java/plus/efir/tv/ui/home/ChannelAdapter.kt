package plus.efir.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.player.LogoLoader

class ChannelAdapter(
    private val onFocused: (Channel) -> Unit,
    private val onOpened: (Channel) -> Unit,
    private val isFavorite: (Channel) -> Boolean,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    private var list: RecyclerView? = null
    private var pendingFocus = -1

    fun submit(next: List<Channel>) {
        items = next
        pendingFocus = -1
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

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
    }

    override fun onViewRecycled(holder: Holder) {
        holder.logo.tag = null
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        val position = holder.bindingAdapterPosition
        if (position == pendingFocus) {
            pendingFocus = -1
            holder.itemView.requestFocus()
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val num: TextView = view.findViewById(R.id.row_num)
        val logo: ImageView = view.findViewById(R.id.row_logo)
        private val name: TextView = view.findViewById(R.id.row_name)
        private val meta: TextView = view.findViewById(R.id.row_meta)
        private val star: TextView = view.findViewById(R.id.row_star)
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
        }

        fun bind(channel: Channel) {
            num.text = channel.number.toString()
            name.text = channel.name
            star.visibility = if (isFavorite(channel)) View.VISIBLE else View.INVISIBLE
            meta.text = if (channel.sources.size > 1) {
                "${channel.group} · ${channel.sources.size} источника"
            } else {
                channel.group
            }
            LogoLoader.into(logo, channel.logoUrl, channel.swatch(TILE_W, TILE_H))
        }
    }

    companion object {
        private const val TILE_W = 160
        private const val TILE_H = 90
    }
}
