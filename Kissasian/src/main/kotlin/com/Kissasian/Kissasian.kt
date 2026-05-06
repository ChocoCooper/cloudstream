package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Kissasian : MainAPI() {
    override var mainUrl = "https://kissasian.cam"
    override var name = "KissAsian"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/drama/" to "Latest Dramas",
        "$mainUrl/movie/" to "Movies",
        "$mainUrl/kshow/" to "Korean Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(pageUrl).document
        val items = doc.select(".bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select(".bsx").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = selectFirst(".tt h2")?.text()?.trim() ?: selectFirst(".tt")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.attr("src")
        val typeText = selectFirst(".typez")?.text()?.trim() ?: ""

        return if (typeText.contains("Movie", ignoreCase = true)) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".ts-post-image")?.attr("src")
        val plot = document.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()
        
        // Grab the rating and tags
        val ratingText = document.selectFirst(".numscore")?.text()
        val tags = document.select(".genxed a").map { it.text() }.toMutableList()
        
        // WORKAROUND: Inject the rating directly into the tags so it appears in the UI
        if (!ratingText.isNullOrBlank()) {
            tags.add(0, "⭐ $ratingText")
        }
        
        val actors = document.select(".split:contains(Casts:) a").map { ActorData(Actor(it.text())) }

        val episodes = document.select(".eplister ul li a, .bxcl ul li a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val epNumText = element.selectFirst(".epl-num")?.text()?.replace(Regex("[^0-9.]"), "")
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: element.text().trim()
            
            newEpisode(href) {
                this.name = epTitle
                this.episode = epNumText?.toFloatOrNull()?.toInt()
            }
        }.reversed()

        val typeText = document.selectFirst(".spe span:contains(Type)")?.text() ?: ""
        val tvType = if (typeText.contains("Movie", ignoreCase = true)) TvType.Movie else TvType.AsianDrama

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags // Score is safely omitted and shown here instead
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags // Score is safely omitted and shown here instead
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val mirrorUrls = document.select("select.mirror option")
            .mapNotNull { it.attr("value") }
            .filter { it.isNotEmpty() }
            .map { fixUrl(it) }
            .toMutableList()

        mirrorUrls.add(data)

        mirrorUrls.distinct().amap { url ->
            try {
                val mirrorDoc = app.get(url).document
                val iframeUrl = mirrorDoc.selectFirst("#pembed iframe")?.attr("src") 
                    ?: mirrorDoc.selectFirst("iframe")?.attr("src")

                if (!iframeUrl.isNullOrEmpty()) {
                    val fixedIframe = fixUrl(iframeUrl)
                    val wasResolved = loadExtractor(fixedIframe, url, subtitleCallback, callback)

                    if (!wasResolved) {
                        universalExtractor(fixedIframe, url, callback)
                    }
                }
            } catch (e: Exception) { }
        }
        return true
    }

    private suspend fun universalExtractor(embedUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(embedUrl, referer = referer).text
            val unpackedSource = getPacked(response) ?: response
            val videoRegex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
            
            videoRegex.findAll(unpackedSource).forEach { match ->
                val streamUrl = match.groupValues[1]
                val domainName = embedUrl.split("//").getOrNull(1)?.split(".")?.getOrNull(0) ?: "Mirror"
                
                if (streamUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(domainName, streamUrl, embedUrl).forEach { callback(it) }
                } else {
                    callback(
                        newExtractorLink(
                            source = domainName,
                            name = domainName,
                            url = streamUrl
                        ).apply {
                            this.referer = embedUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) { }
    }
}
