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
        val rating = document.selectFirst(".numscore")?.text()?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()?.times(10)?.toInt()
        val tags = document.select(".genxed a").map { it.text() }
        val actors = document.select(".split:contains(Casts:) a").map { ActorData(Actor(it.text())) }

        val episodes = document.select(".eplister ul li a, .bxcl ul li a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val epNumText = element.selectFirst(".epl-num")?.text()?.replace(Regex("[^0-9.]"), "")
            val epName = element.selectFirst(".epl-title")?.text()?.trim() ?: element.text().trim()
            newEpisode(href) {
                this.name = epName
                this.episode = epNumText?.toFloatOrNull()?.toInt()
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.rating = rating
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

        // 1. Collect all potential mirror URLs from the dropdown
        val mirrorUrls = document.select("select.mirror option")
            .mapNotNull { it.attr("value") }
            .filter { it.isNotEmpty() }
            .map { fixUrl(it) }
            .toMutableList()

        // 2. Add the current page as well (in case the default player is embedded there)
        mirrorUrls.add(data)

        // 3. Process all unique servers in parallel
        mirrorUrls.distinct().apmap { url ->
            try {
                val mirrorDoc = app.get(url).document
                val iframeUrl = mirrorDoc.selectFirst("#pembed iframe")?.attr("src") 
                    ?: mirrorDoc.selectFirst("iframe")?.attr("src")

                if (!iframeUrl.isNullOrEmpty()) {
                    val fixedIframe = fixUrl(iframeUrl)
                    
                    // STEP A: Try internal Cloudstream extractors (Streamtape, Doodstream, etc.)
                    val wasResolved = loadExtractor(fixedIframe, url, subtitleCallback, callback)

                    // STEP B: Universal Fallback for unknown servers
                    if (!wasResolved) {
                        universalExtractor(fixedIframe, url, callback)
                    }
                }
            } catch (e: Exception) {
                // Log or ignore failed mirror fetches
            }
        }
        return true
    }

    /**
     * The Universal Scraper:
     * Logic to find playable links in raw HTML/JS of unknown servers
     */
    private suspend fun universalExtractor(
        embedUrl: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Fetch the embed page source
            val response = app.get(embedUrl, referer = referer).text
            
            // Check for and unpack 'Dean Edwards' packer (common in video hosts)
            val unpackedSource = getPacked(response) ?: response

            // Comprehensive Regex to find .m3u8 or .mp4 sources
            // Handles formats like: file: "URL", src: 'URL', source src="URL"
            val videoRegex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
            
            videoRegex.findAll(unpackedSource).forEach { match ->
                val streamUrl = match.groupValues[1]
                val domainName = embedUrl.split("//").getOrNull(1)?.split(".")?.getOrNull(0) ?: "Mirror"
                
                if (streamUrl.contains(".m3u8")) {
                    // Use M3u8Helper to automatically get multiple qualities from the playlist
                    M3u8Helper.generateM3u8(domainName, streamUrl, embedUrl).forEach { link ->
                        callback(link)
                    }
                } else {
                    // Direct MP4 link
                    callback(
                        newExtractorLink(
                            source = domainName,
                            name = domainName,
                            url = streamUrl,
                            referer = embedUrl,
                            quality = Qualities.P1080.value, // Defaulting to high
                            isM3u8 = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Extraction failed for this specific server
        }
    }
}
