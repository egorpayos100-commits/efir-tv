package plus.efir.tv.ui.home

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import plus.efir.tv.R

/**
 * The five words across the top of the screen. The rail on the left is gone: one line of tabs, and
 * every shelf of the screen is a press away along it.
 *
 * Walking the bar is the gesture: the cursor lands on a word and the screen below changes at once,
 * so he never has to press ОК just to look at another tab.
 */
class TabAdapter(
    private val labels: List<String>,
    private val onPicked: (Int) -> Unit,
) : RecyclerView.Adapter<TabAdapter.Holder>() {

    private var selected = 0
    private var bar: RecyclerView? = null
    private var pendingFocus = -1

    fun select(index: Int) {
        val next = index.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        if (next == selected) return
        val was = selected
        selected = next
        notifyItemChanged(was)
        notifyItemChanged(next)
    }

    fun requestFocusAt(position: Int) {
        if (position !in labels.indices) return
        val rv = bar ?: return
        // A bar that scrolled has no holder for the word yet: focus waits for the layout instead of
        // being dropped, which is what made half of the tabs unreachable.
        rv.post {
            val existing = rv.findViewHolderForAdapterPosition(position)
            if (existing != null) {
                existing.itemView.requestFocus()
            } else {
                pendingFocus = position
                rv.scrollToPosition(position)
                rv.postDelayed({
                    if (pendingFocus == position) {
                        rv.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                    }
                }, 40)
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        bar = recyclerView
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        if (holder.bindingAdapterPosition == pendingFocus) {
            pendingFocus = -1
            holder.itemView.requestFocus()
        }
    }

    override fun getItemCount() = labels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false) as TextView
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

    inner class Holder(private val label: TextView) : RecyclerView.ViewHolder(label) {

        private var chosen = false

        init {
            label.setOnClickListener { if (bindingAdapterPosition >= 0) onPicked(bindingAdapterPosition) }
            label.setOnFocusChangeListener { _, focused ->
                paint(focused)
                if (!focused) return@setOnFocusChangeListener
                // A cursor that arrives with the layout is not a choice: left alone it lands on
                // «Эфир» and this listener flipped the whole screen there by itself, which is what
                // pulled him out of the cinema. Walking the bar is a sideways press, and that is
                // read where the key comes in — here the cursor just goes back to the open tab.
                val at = bindingAdapterPosition
                if (at >= 0 && at != selected) requestFocusAt(selected)
            }
        }

        fun bind(position: Int) {
            label.text = labels[position]
            chosen = position == selected
            label.setTypeface(null, if (chosen) Typeface.BOLD else Typeface.NORMAL)
            paint(label.isFocused)
        }

        private fun paint(focused: Boolean) {
            label.setTextColor(
                ContextCompat.getColor(
                    label.context,
                    if (focused || chosen) R.color.text_primary else R.color.text_muted
                )
            )
        }
    }
}
