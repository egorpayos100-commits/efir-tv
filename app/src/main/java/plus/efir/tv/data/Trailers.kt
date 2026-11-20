package plus.efir.tv.data

import android.net.Uri

/**
 * A trailer is a YouTube address pasted into the playlist. The box watches it in the same WebView
 * as a VK film, but an embed loaded straight into a WebView has no page of its own: the player
 * request arrives without a Referer and YouTube answers «Ошибка 153».
 *
 * The wrapper page used to live on my site, and that was the black screen: the box's provider
 * filters that domain, so the page never loaded, and the fallback — the full YouTube watch page
 * inside a WebView — draws a black player with a button a D-pad cannot reach. The same wrapper is
 * now written here and loaded from this document, which keeps the Referer and needs no network.
 */
object Trailers {

    /** The origin the wrapper document claims: the iframe request carries it as the Referer. */
    const val BASE = "https://list-5be13734fc5b-wiru0jq6tik.qoder.website/"

    private val ID = Regex("""(?:v=|youtu\.be/|/embed/|/shorts/)([\w-]{11})""")

    fun embedUrl(raw: String?): String? {
        val id = idOf(raw) ?: return null
        return Uri.Builder()
            .scheme("https")
            .authority("www.youtube.com")
            .path("/embed/$id")
            .appendQueryParameter("autoplay", "1")
            .appendQueryParameter("playsinline", "1")
            .appendQueryParameter("rel", "0")
            .appendQueryParameter("modestbranding", "1")
            .build()
            .toString()
    }

    /** The video id, or null when the row carries something that is not a YouTube link. */
    fun idOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return ID.find(text)?.groupValues?.get(1)
    }

    /** Last resort on the page: embedding refused, so the video opens where it always plays. */
    fun watchUrl(raw: String?): String? =
        idOf(raw)?.let { "https://www.youtube.com/watch?v=$it&hl=ru" }

    /**
     * The wrapper the WebView loads with itself as the base address. It reports what the player is
     * doing in `window.__efirState`, because an iframe is a different origin and nothing outside it
     * can see the picture: without that reading the app can only guess, and guessing meant tapping
     * the centre of a trailer that was already playing.
     */
    fun html(id: String): String = """
        <!doctype html>
        <html lang="ru"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
        #player{position:absolute;top:0;left:0;width:100%;height:100%}</style>
        </head><body><div id="player"></div>
        <script>
        window.__efirState='loading';window.__efirCode=0;
        var tag=document.createElement('script');
        tag.src='https://www.youtube.com/iframe_api';
        tag.onerror=function(){window.__efirState='error';window.__efirCode=-1;};
        document.head.appendChild(tag);
        window.onYouTubeIframeAPIReady=function(){
          var player=new YT.Player('player',{
            width:'100%',height:'100%',videoId:'$id',
            playerVars:{autoplay:1,playsinline:1,rel:0,hl:'ru',iv_load_policy:3,modestbranding:1},
            events:{
              onReady:function(e){window.__efirState='cued';e.target.playVideo();},
              onStateChange:function(e){
                var names={'-1':'cued','0':'ended','1':'playing','2':'paused','3':'buffering','5':'cued'};
                window.__efirState=names[e.data]||'loading';
              },
              onError:function(e){window.__efirState='error';window.__efirCode=e.data;}
            }
          });
        };
        </script></body></html>
    """.trimIndent()
}
