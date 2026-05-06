package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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

        // Extract metadata
        val title = document.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".thumb img, .ts-post-image")?.attr("src")
        val plot = document.selectFirst(".entry-content p, .desc, .mindes")?.text()?.trim()

        // Extract episodes
        val episodes = document.select(".eplister ul li a, .bxcl ul li a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val epNumText = element.selectFirst(".epl-num")?.text()?.replace(Regex("[^0-9.]"), "")
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim()
            
            // Fallback to the whole text if specific title class isn't found
            val epName = if (epTitle.isNullOrEmpty()) element.text().trim() else epTitle
            val epNum = epNumText?.toFloatOrNull()?.toInt()

            // FIX: Using the newEpisode builder instead of the deprecated Episode() constructor
            newEpisode(href) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed() // Reverse so Episode 1 is at the top of the UI

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ==========================================
    // 3. LOAD LINKS: Finds the video players
    // ==========================================
    // FIX: Swapped subtitleCallback and callback order to match the new API
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Grab the main player iframe if it exists
        val mainIframe = document.selectFirst("#pembed iframe")?.attr("src")
        if (!mainIframe.isNullOrEmpty()) {
            loadExtractor(fixUrl(mainIframe), data, subtitleCallback, callback)
        }

        // Look for alternative mirror servers
        val mirrors = document.select("select.mirror option")
        mirrors.forEach { mirror ->
            val value = mirror.attr("value")
            
            // Skip the empty "Select Video Server" placeholder
            if (value.isNotEmpty()) {
                val mirrorUrl = fixUrl(value)
                
                // Fetch the mirror's HTML to find its iframe
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
