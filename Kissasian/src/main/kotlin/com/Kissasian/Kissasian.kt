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

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".bsx").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".tt h2")?.text() 
                ?: element.selectFirst(".tt")?.text() 
                ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val image = element.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".ts-post-image")?.attr("src")
        val plot = document.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()
        
        // FIX: Replaced deprecated rating with score
        val rating = document.selectFirst(".numscore")?.text()?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()
        
        val tags = document.select(".genxed a").map { it.text() }
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

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.score = rating?.times(10)?.toInt()
            this.tags = tags
            this.actors = actors
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

        // FIX: Replaced deprecated apmap with amap
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
            } catch (e: Exception) {
                // Handle or ignore
            }
        }
        return true
    }

    private suspend fun universalExtractor(
        embedUrl: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(embedUrl, referer = referer).text
            val unpackedSource = getPacked(response) ?: response
            val videoRegex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
            
            videoRegex.findAll(unpackedSource).forEach { match ->
                val streamUrl = match.groupValues[1]
                val domainName = embedUrl.split("//").getOrNull(1)?.split(".")?.getOrNull(0) ?: "Mirror"
                
                if (streamUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(domainName, streamUrl, embedUrl).forEach { link ->
                        callback(link)
                    }
                } else {
                    // FIX: Corrected newExtractorLink parameter logic
                    callback(
                        ExtractorLink(
                            source = domainName,
                            name = domainName,
                            url = streamUrl,
                            referer = embedUrl,
                            quality = Qualities.P1080.value,
                            isM3u8 = false
                        )
                    )
                }
            }
        } catch (e: Exception) { }
    }
}
