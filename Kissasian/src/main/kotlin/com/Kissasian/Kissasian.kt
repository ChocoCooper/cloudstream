package com.example.kissasian

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class KissAsian : MainAPI() {

    override var mainUrl  = "https://kissasian.cam"
    override var name     = "KissAsian"
    override val lang     = "en"
    override val hasMainPage = true
    override val hasSearch   = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie
    )

    // ── CloudFlare / bot-check bypass ──────────────────────────────────────
    private val cfKiller = CloudflareKiller()

    private suspend fun fetchDoc(url: String) =
        app.get(url, interceptor = cfKiller).document

    // ── Main page sections ─────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/drama/"        to "Latest Dramas",
        "$mainUrl/movie/"        to "Movies",
        "$mainUrl/kshow/"        to "Korean Shows",
        "$mainUrl/drama-list/"   to "Drama List (A–Z)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Most kissasian-style sites use /page/N/ pagination
        val pageUrl = if (page == 1) request.data
                      else "${request.data.trimEnd('/')}/page/$page/"
        val doc   = fetchDoc(pageUrl)
        val items = doc.select(".bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ─────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetchDoc("$mainUrl/?s=${query.encodeUrl()}")
        return doc.select(".bsx").mapNotNull { it.toSearchResult() }
    }

    // ── Shared element → SearchResponse converter ──────────────────────────
    private fun Element.toSearchResult(): SearchResponse? {
        val a      = selectFirst("a") ?: return null
        val href   = fixUrl(a.attr("href"))

        val title  = selectFirst(".tt h2")?.text()?.trim()
            ?: selectFirst(".tt")?.text()?.trim()
            ?: return null

        val poster = selectFirst("img")?.run {
            attr("data-src").ifBlank { attr("src") }
        }

        val typeText = selectFirst(".typez")?.text()?.trim() ?: ""

        return if (typeText.contains("Movie", ignoreCase = true)) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }

    // ── Series / Movie detail page ─────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(url)

        val title = doc.selectFirst(
            ".entry-title, h1.entry-title, .infox h1, .entry-header h1"
        )?.text()?.trim() ?: return null

        val poster = doc.selectFirst(
            ".thumb img, .poster img, .entry-content img, img.attachment-post-thumbnail"
        )?.run { attr("data-src").ifBlank { attr("src") } }

        val plot = doc.selectFirst(
            ".entry-content p, .synp p, .spe p, [itemprop=description]"
        )?.text()?.trim()

        val tags = doc.select(".genxed a, .sgeneros a, .genre a").map { it.text() }

        val year = doc.selectFirst(".spe span:contains(Year) a, .info-content span:contains(Year)")
            ?.text()?.trim()?.takeLast(4)?.toIntOrNull()

        val typeText = doc.selectFirst(".spe span:contains(Type), .typez")?.text() ?: ""
        val isMovie  = typeText.contains("Movie", ignoreCase = true)

        // ── Episode list ───────────────────────────────────────────────────
        // Episodes are usually listed newest-first; we reverse to Ep 1 at top
        val rawEpisodes = doc.select(
            ".eplister ul li a, .bxcl ul li a, #episode_by_temp ul li a"
        ).map { ep ->
            val epHref  = fixUrl(ep.attr("href"))
            val numText = ep.selectFirst(".epl-num")?.text()?.trim()
            val epTitle = ep.selectFirst(".epl-title")?.text()?.trim() ?: ep.text().trim()
            val label   = if (numText != null) "$numText – $epTitle" else epTitle
            val epNum   = numText?.filter { it.isDigit() }?.toIntOrNull()
            Episode(data = epHref, name = label, episode = epNum)
        }.reversed()

        // ── Build response ─────────────────────────────────────────────────
        return if (isMovie || rawEpisodes.size == 1) {
            newMovieLoadResponse(
                title, url, TvType.Movie,
                dataUrl = rawEpisodes.firstOrNull()?.data ?: url
            ) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
                this.year      = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, rawEpisodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
                this.year      = year
            }
        }
    }

    // ── Link extraction — the heart of multi-server support ───────────────
    //
    //  Strategy mirrors the Python scraper exactly:
    //    1. Check #pembed iframe on the episode page  → Main Server
    //    2. For every <option> in select.mirror       → fetch that mirror
    //       sub-page and grab its #pembed iframe      → Server N
    //
    //  Every embed URL is then handed to CloudStream's loadExtractor() which
    //  handles StreamTape, Dood, VidHide, FileMoon, DS2Play, etc.
    // ──────────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc     = fetchDoc(data)
        val servers = mutableListOf<Pair<String, String>>()   // (label, embedUrl)

        // ── 1. Main / default embed ────────────────────────────────────────
        doc.selectFirst("#pembed iframe, .mplayer iframe")
            ?.attr("src")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { servers.add("Main Server" to fixUrl(it)) }

        // ── 2. Mirror dropdown ─────────────────────────────────────────────
        //
        //  Each <option value="?mirror=2"> (or similar relative URL) points
        //  to a sub-page that contains its own #pembed iframe.
        doc.select("select.mirror option, .mirrorlink option").forEach { opt ->
            val optValue = opt.attr("value").trim()
            val optName  = opt.text().trim()

            // Skip empty placeholder ("Choose Server", etc.)
            if (optValue.isEmpty() || optName.isEmpty() ||
                optName.contains("choose", ignoreCase = true) ||
                optName.contains("select", ignoreCase = true)
            ) return@forEach

            val mirrorUrl = fixUrl(optValue).let { raw ->
                // If it looks like a relative path/query, resolve against episode URL
                if (raw.startsWith("http")) raw else "$data${raw.trimStart('/')}"
            }

            try {
                val mDoc    = fetchDoc(mirrorUrl)
                val mIframe = mDoc.selectFirst("#pembed iframe, .mplayer iframe")
                mIframe?.attr("src")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { servers.add(optName to fixUrl(it)) }
            } catch (e: Exception) {
                // Mirror fetch failed — skip silently
            }
        }

        if (servers.isEmpty()) return false

        // ── 3. Feed every embed URL to the extractor engine ───────────────
        //
        //  CloudStream has built-in extractors for:
        //    StreamTape, Dood, FileMoon, VidHide, VidPlay, DS2Play,
        //    StreamWish, Mp4upload, JustPlayer variants, and many more.
        //
        //  For hosts without a built-in extractor we fall back to a
        //  generic iframe/direct-link attempt via loadExtractor().
        servers.forEachIndexed { idx, (label, embedUrl) ->
            try {
                // loadExtractor returns false for unknown hosts but won't throw
                val loaded = loadExtractor(
                    url              = embedUrl,
                    referer          = data,
                    subtitleCallback = subtitleCallback,
                    callback         = callback
                )

                // Fallback: if the embed URL itself is a playable stream
                if (!loaded) {
                    handleUnknownEmbed(embedUrl, label, data, callback)
                }
            } catch (e: Exception) {
                // Individual server failure should not abort the rest
            }
        }

        return true
    }

    // ── Fallback for embed hosts without a registered extractor ───────────
    //
    //  Some hosts (e.g. mdbekjwqa.pw, strcloud.in, krakenfiles) may not be
    //  registered in the CloudStream extractor list.  We do a lightweight
    //  page fetch and look for a raw video src as a last resort.
    private suspend fun handleUnknownEmbed(
        embedUrl : String,
        label    : String,
        referer  : String,
        callback : (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                embedUrl,
                referer  = referer,
                headers  = mapOf("Accept" to "*/*"),
                interceptor = cfKiller
            ).document

            // Common patterns: <source src="…">, <video src="…">, file: "…"
            val videoSrc =
                doc.selectFirst("source[src]")?.attr("src")
                    ?: doc.selectFirst("video[src]")?.attr("src")
                    ?: Regex("""["\']?(?:file|src)["\']?\s*:\s*["\']([^"\']+\.m3u8[^"\']*)["\']""")
                        .find(doc.html())?.groupValues?.get(1)
                    ?: Regex("""["\']?(?:file|src)["\']?\s*:\s*["\']([^"\']+\.mp4[^"\']*)["\']""")
                        .find(doc.html())?.groupValues?.get(1)

            videoSrc?.let { src ->
                callback(
                    ExtractorLink(
                        source   = this.name,
                        name     = "$name – $label",
                        url      = fixUrl(src),
                        referer  = embedUrl,
                        quality  = Qualities.Unknown.value,
                        isM3u8   = src.contains(".m3u8")
                    )
                )
            }
        } catch (e: Exception) {
            // Best-effort; silently ignored
        }
    }

    // ── Tiny helpers ───────────────────────────────────────────────────────
    private fun String.encodeUrl() =
        java.net.URLEncoder.encode(this, "UTF-8")
}
