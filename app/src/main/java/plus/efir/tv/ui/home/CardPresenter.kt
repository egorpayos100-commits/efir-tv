package plus.efir.tv.ui.home

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import plus.efir.tv.R
import plus.efir.tv.data.Channel

class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val card = ImageCardView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(
                context.resources.getDimensionPixelSize(R.dimen.card_image_width),
                context.resources.getDimensionPixelSize(R.dimen.card_image_height)
            )
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.card_info_bg))
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as Channel
        val card = viewHolder.view as ImageCardView
        val context = card.context
        card.titleText = channel.name
        card.contentText = "${channel.group} · ${sourceLabel(channel)}"
        card.setMainImageAdjustViewBounds(true)
        card.mainImageView.setImageBitmap(
            channel.swatch(
                context.resources.getDimensionPixelSize(R.dimen.card_image_width),
                context.resources.getDimensionPixelSize(R.dimen.card_image_height)
            )
        )
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImageView.setImageBitmap(null)
    }

    private fun sourceLabel(channel: Channel): String = when {
        channel.sources.isEmpty() -> "нет источника"
        channel.sources.size == 1 -> "1 источник"
        else -> "${channel.sources.size} источника"
    }
}
