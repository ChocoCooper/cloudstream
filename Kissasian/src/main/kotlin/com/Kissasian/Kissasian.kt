package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getPacked

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

    // ==========================================
    // 1. SEARCH: Finds shows based on a query
    // ==========================================
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

    // ==========================================
    // 2. LOAD: Scrapes the series page for episodes
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".thumb img, .ts-post-image")?.attr("src")
        val plot = document.selectFirst(".entry-content p, .desc, .mindes")?.text()?.trim()

        val episodes = document.select(".eplister ul li a, .bxcl ul li a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val epNumText = element.selectFirst(".epl-num")?.text()?.replace(Regex("[^0-9.]"), "")
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim()
            
            val epName = if (epTitle.isNullOrEmpty()) element.text().trim() else epTitle
            val epNum = epNumText?.toFloatOrNull()?.toInt()

            newEpisode(href) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ==========================================
    // 3. LOAD LINKS: Finds the video players
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Check Main Player
        val mainIframe = document.selectFirst("#pembed iframe")?.attr("src")
        if (!mainIframe.isNullOrEmpty()) {
            loadExtractor(fixUrl(mainIframe), data, subtitleCallback, callback)
        }

        // 2. Check Mirrors
        val mirrors = document.select("select.mirror option")
        mirrors.forEach { mirror ->
            val value = mirror.attr("value")
            if (value.isNotEmpty()) {
                val mirrorUrl = fixUrl(value)
                val mirrorDoc = app.get(mirrorUrl).document
                val mirrorIframe = mirrorDoc.selectFirst("#pembed iframe")?.attr("src")
                
                if (!mirrorIframe.isNullOrEmpty()) {
                    loadExtractor(fixUrl(mirrorIframe), data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}

// ==========================================
// 4. CUSTOM EXTRACTORS (Bypass 404s)
// ==========================================

class LuluvidExtractor : ExtractorApi() {
    override val name = "Luluvid"
    override val mainUrl = "https://luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinks = mutableListOf<ExtractorLink>()
        try {
            // Fetch the iframe HTML
            val response = app.get(url, referer = referer).text
            
            // Luluvid and similar hosts often hide links in packed JavaScript
            val unpacked = getPacked(response) ?: response

            // Generic Regex to catch standard m3u8 or mp4 files in the source
            val videoUrlRegex = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
            val match = videoUrlRegex.find(unpacked)
            
            if (match != null) {
                val finalVideoUrl = match.groupValues[1]
                extractedLinks.add(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = finalVideoUrl,
                        referer = url,
                        quality = Qualities.P1080.value, // Defaulting to 1080p, adjust if they provide multi-quality
                        isM3u8 = finalVideoUrl.contains(".m3u8")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return extractedLinks
    }
}

class StrcloudExtractor : ExtractorApi() {
    override val name = "Strcloud"
    override val mainUrl = "https://strcloud.in"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinks = mutableListOf<ExtractorLink>()
        try {
            val response = app.get(url, referer = referer).text
            val unpacked = getPacked(response) ?: response

            // Generic Regex to catch standard m3u8 or mp4 files
            val videoUrlRegex = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
            val match = videoUrlRegex.find(unpacked)
            
            if (match != null) {
                val finalVideoUrl = match.groupValues[1]
                extractedLinks.add(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = finalVideoUrl,
                        referer = url,
                        quality = Qualities.P1080.value,
                        isM3u8 = finalVideoUrl.contains(".m3u8")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return extractedLinks
    }
}
