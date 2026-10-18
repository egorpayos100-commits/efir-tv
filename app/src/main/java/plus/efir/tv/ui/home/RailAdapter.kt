package plus.efir.tv.ui.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.R
import plus.efir.tv.data.Channel
import plus.efir.tv.data.Kind

class Category(val title: String, val channels: List<Channel>) {
    val initial: String get() = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    /** Film shelves play only on ОК and seek instead of zapping in fullscreen. */
    val isFilmShelf: Boolean get() = channels.firstOrNull()?.kind == Kind.Film
}

class RailAdapter(
    private val onPicked: (Int) -> Unit,
) : RecyclerView.Adapter<RailAdapter.Holder>() {

    private var items: List<Category> = emptyList()
    private var rail: RecyclerView? = null
    private var pendingFocus = -1

    fun submit(next: List<Category>) {
        items = next
        notifyDataSetChanged()
    }

    fun requestFocusAt(position: Int) {
        if (position !in items.indices) return
        val existing = rail?.findViewHolderForAdapterPosition(position)
        if (existing != null) {
            existing.itemView.requestFocus()
        } else {
            pendingFocus = position
            rail?.scrollToPosition(position)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        rail = recyclerView
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        if (holder.bindingAdapterPosition == pendingFocus) {
            pendingFocus = -1
            holder.itemView.requestFocus()
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tile: TextView = view.findViewById(R.id.rail_tile)
        private val label: TextView = view.findViewById(R.id.rail_label)

        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition >= 0) onPicked(bindingAdapterPosition)
            }
        }

        fun bind(category: Category) {
            label.text = category.title
            tile.text = category.initial
            val hue = (Math.abs(category.title.hashCode()) % 360).toFloat()
            tile.setTextColor(Color.HSVToColor(floatArrayOf(hue, 0.45f, 1f)))
        }
    }
}
