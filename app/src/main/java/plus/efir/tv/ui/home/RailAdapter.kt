package plus.efir.tv.ui.home

import plus.efir.tv.data.Channel
import plus.efir.tv.data.Kind

/** `shelfId` marks a shelf that is not a channel group: search, the playlist pane, favourites. */
class Category(val title: String, val channels: List<Channel>, val shelfId: String? = null) {

    /** The playlist pane: rows that open a URL field, not media to play. */
    val isSettings: Boolean get() = shelfId == SETTINGS_SHELF

    /** Search is a live shelf: it zaps on focus like any channel list does. */
    val isSearch: Boolean get() = shelfId == SEARCH_SHELF

    /** The starred shelf, which is empty until he starts pressing → and ОК on a flag. */
    val isFavorites: Boolean get() = shelfId == FAVORITES_SHELF

    /** Starred films live on their own shelf: a cinema row inside «Избранные» among channels is mush. */
    val isFavoriteFilms: Boolean get() = shelfId == FAVORITES_FILMS_SHELF

    /** The guide: every channel as a tile that says what plays on it right now. */
    val isGuide: Boolean get() = shelfId == GUIDE_SHELF

    /** Film shelves play only on ОК and seek instead of zapping in fullscreen. */
    val isFilmShelf: Boolean
        get() = !isSettings && !isSearch && !isFavorites &&
            (isFavoriteFilms || shelfId == FILM_SHELF || channels.firstOrNull()?.kind == Kind.Film)

    companion object {
        const val SETTINGS_SHELF = "settings"
        const val SEARCH_SHELF = "search"
        const val FAVORITES_SHELF = "favorites"
        const val FAVORITES_FILMS_SHELF = "favorites_films"
        const val GUIDE_SHELF = "guide"
        const val FILM_SHELF = "films"

        /** The single updater row: a shelf of its own, so the remote needs only arrows and ОК. */
        const val UPDATE_SHELF = "update"
    }
}
