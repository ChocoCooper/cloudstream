package com.Tamilian

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale

class Tamilian : MainAPI() {
    override var name = "Tamilian"
    override var mainUrl = "https://tamilian.io"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie)

    // --- HELPER: Safely handles relative URLs ---
    private fun String.toAbsoluteUrl(): String {
        if (this.isBlank()) return ""
        if (this.startsWith("http")) return this
        if (this.startsWith("//")) return "https:$this"
        return if (this.startsWith("/")) "$mainUrl$this" else "$mainUrl/$this"
    }

    // --- HELPER: Cleans "the-batman-d3d9446" into "The Batman" ---
    private fun cleanTitleFromUrl(url: String): String {
        val slug = url.trimEnd('/').split("/").last()
        val withoutId = slug.replace(Regex("-[a-f0-9]{7,}\$"), "")
        return withoutId.replace("-", " ").split(" ").joinToString(" ") { 
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString() } 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home/").document
        
        val movies = doc.select("a[href*='/movie/']").mapNotNull { element ->
            val href = element.attr("href")
            if (href.contains("watching.html") || href.isBlank()) return@mapNotNull null
            
            val url = href.toAbsoluteUrl()
            val title = cleanTitleFromUrl(url)
            
            val img = element.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                this.posterUrl = poster?.toAbsoluteUrl()
            }
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("Latest Movies", movies)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search/$query").document
        
        return doc.select("a[href*='/movie/']").mapNotNull { element ->
            val href = element.attr("href")
            if (href.contains("watching.html") || href.isBlank()) return@mapNotNull null
            
            val url = href.toAbsoluteUrl()
            val title = cleanTitleFromUrl(url)
            
            val img = element.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                this.posterUrl = poster?.toAbsoluteUrl()
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst(".mvic-desc h3")?.text() ?: cleanTitleFromUrl(url)
        val plot = doc.selectFirst(".desc")?.text()
        val yearText = doc.selectFirst(".mvici-right p:contains(Release:) a")?.text()
        val year = yearText?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val styleAttr = doc.selectFirst(".mvic-thumb")?.attr("style")
        val poster = styleAttr?.let { Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1) }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url 
        ) {
            this.posterUrl = poster?.toAbsoluteUrl()
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.endsWith("/")) "${data}watching.html" else "$data/watching.html"
        val baseHeaders = mapOf("Referer" to data)
        
        // --- STEP 1: Get the Internal Movie ID ---
        val watchRes = app.get(watchUrl, headers = baseHeaders).text
        val movieIdMatch = Regex("""movie\s*=\s*\{[^}]*id:\s*"(\d+)"""").find(watchRes)
        val movieId = movieIdMatch?.groupValues?.get(1) ?: return false

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to watchUrl
        )

        // --- STEP 2: Fetch the Hidden Server Buttons ---
        val serverApiUrl = "$mainUrl/ajax/movie/episode/servers/${movieId}_1_full"
        val servRes = app.get(serverApiUrl, headers = ajaxHeaders)
        if (!servRes.isSuccessful) return false

        val serverBtns = servRes.document.select("a[data-id]")
        if (serverBtns.isEmpty()) return false

        val targetBtn = serverBtns.firstOrNull { it.text().contains("MegaCloud", true) } 
            ?: serverBtns.firstOrNull() 
            ?: return false
        
        val cleanDataId = targetBtn.attr("data-id").replace("\"", "").replace("\\", "").replace("'", "")
        val cleanDataName = targetBtn.attr("data-name").replace("\"", "").replace("\\", "").replace("'", "")

        val fullDataId = if (cleanDataName.isNotBlank() && !cleanDataId.endsWith(cleanDataName)) {
            "${cleanDataId}_${cleanDataName}"
        } else {
            cleanDataId
        }

        // --- STEP 3: Fetch the Embed Link ---
        val sourcesUrl = "$mainUrl/ajax/movie/episode/server/sources/$fullDataId"
        val sourceRes = app.get(sourcesUrl, headers = ajaxHeaders)
        if (!sourceRes.isSuccessful) return false

        var finalLink: String? = null
        try { finalLink = sourceRes.parsedSafe<LinkResponse>()?.link } catch (e: Exception) {}

        if (finalLink == null) {
            val match = Regex("""(https?://(?:embedojo\.net|megacloud\.[a-z]+)/[^\s"\'<>\\]+)""").find(sourceRes.text)
            finalLink = match?.groupValues?.get(1)?.replace("\\", "")
        }

        // --- STEP 4: Trigger the POST Request to extract the .m3u8 ---
        if (finalLink != null) {
            val token = finalLink.substringAfterLast("/")
            val embedHost = if (finalLink.contains("megacloud")) "https://megacloud.tv" else "https://embedojo.net"

            val postHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to finalLink,
                "Origin" to embedHost,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )

            // Hit the /player/index.php endpoint
            val videoDataRes = app.post(
                "$embedHost/player/index.php?data=$token&do=getVideo",
                headers = postHeaders
            )

            val parsedData = videoDataRes.parsedSafe<VideoData>()
            var sourceUrl = parsedData?.videoSource

            if (sourceUrl.isNullOrBlank()) {
                val match = Regex(""""videoSource"\s*:\s*"([^"]+)"""").find(videoDataRes.text)
                sourceUrl = match?.groupValues?.get(1)?.replace("\\", "")
            }

            // Extract Subtitles if they exist
            parsedData?.tracks?.forEach { track ->
                if (track.kind == "captions" && !track.file.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        SubtitleFile(track.label ?: "Subtitles", track.file)
                    )
                }
            }

            // Construct the final player headers
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to finalLink,
                "Origin" to embedHost,
                "Accept" to "*/*"
            )

            if (!sourceUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name = "Tamilian HD",
                        source = "Tamilian",
                        url = sourceUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = finalLink
                        this.headers = playerHeaders // This prevents ExoPlayer from crashing!
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        }

        return false
    }

    data class LinkResponse(
        @JsonProperty("link") val link: String?
    )

    data class Track(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class VideoData(
        @JsonProperty("videoSource") val videoSource: String?,
        @JsonProperty("tracks") val tracks: List<Track>?
    )
}
