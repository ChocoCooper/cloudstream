package com.Tamilian

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList // Added for multiple sliders
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

class Tamilian : MainAPI() {
    override var name = "Tamilian"
    override var mainUrl = HOST
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        const val HOST = "https://embedojo.net"
        const val TMDB_API = "https://api.tmdb.org/3"
        const val TMDB_KEY = "fb7bb23f03b6994dafc674c074d01761" 
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // URL 1: Latest Tamil Movies
        val latestUrl = "$TMDB_API/discover/movie?api_key=$TMDB_KEY&with_original_language=ta&vote_count.gte=2&sort_by=primary_release_date.desc&page=$page"
        // URL 2: Tamil Dubbed Movies (using unblocked TMDB_API and appending &page=$page for infinite scroll)
        val dubbedUrl = "$TMDB_API/discover/movie?api_key=$TMDB_KEY&with_original_language=en&with_spoken_languages=ta&sort_by=popularity.desc&page=$page"

        // Fetch data for both sliders
        val latestResponse = app.get(latestUrl).parsedSafe<TmdbResponse>()
        val dubbedResponse = app.get(dubbedUrl).parsedSafe<TmdbResponse>()

        // 1. Map Latest Tamil Movies and strictly limit to 12 items
        val latestItems = latestResponse?.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            
            newMovieSearchResponse(
                name = title,
                url = "$mainUrl/$id",
                type = TvType.Movie
            ) {
                this.posterUrl = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            }
        }?.take(12) ?: listOf() // <-- Limits to exactly 12 items

        // 2. Map Tamil Dubbed Movies and strictly limit to 10 items
        val dubbedItems = dubbedResponse?.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            
            newMovieSearchResponse(
                name = title,
                url = "$mainUrl/$id",
                type = TvType.Movie
            ) {
                this.posterUrl = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            }
        }?.take(10) ?: listOf() // <-- Limits to exactly 10 items

        // 3. Return multiple sliders inside the HomePageResponse
        return HomePageResponse(
            listOf(
                HomePageList("Latest Tamil Movies", latestItems),
                HomePageList("Tamil Dubbed Movies", dubbedItems)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$TMDB_API/search/movie?api_key=$TMDB_KEY&with_original_language=ta&query=$query"
        val response = app.get(url).parsedSafe<TmdbResponse>()

        return response?.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            
            newMovieSearchResponse(
                name = title,
                url = "$mainUrl/$id",
                type = TvType.Movie
            ) {
                this.posterUrl = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val tmdbId = url.substringAfterLast("/")
        val reqUrl = "$TMDB_API/movie/$tmdbId?api_key=$TMDB_KEY&with_original_language=ta"
        
        val details = app.get(reqUrl).parsedSafe<TmdbDetails>() ?: return null

        return newMovieLoadResponse(
            name = details.title ?: "",
            url = url,
            type = TvType.Movie,
            dataUrl = url 
        ) {
            this.posterUrl = details.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.plot = details.overview
            this.year = details.release_date?.split("-")?.firstOrNull()?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = data.substringAfterLast("/")
        val pageUrl = "$HOST/tamil/tmdb/$tmdbId"
        
        val script = app.get(pageUrl)
            .document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()?.let { getAndUnpack(it) }

        val token = script?.substringAfter("FirePlayer(\"")?.substringBefore("\",") ?: return false
        
        val apiHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to pageUrl,
            "Origin" to HOST,
            "Accept" to "*/*",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )

        val m3u8 = app.post("$HOST/player/index.php?data=$token&do=getVideo", headers = apiHeaders)
            .parsedSafe<VideoData>()
            
        val sourceUrl = m3u8?.videoSource ?: return false
        
        safeApiCall {
            callback.invoke(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = sourceUrl,
                    type = ExtractorLinkType.M3U8 
                ) {
                    this.referer = pageUrl
                    this.headers = apiHeaders
                    this.quality = Qualities.P1080.value 
                }
            )
        }
        return true
    }

    // --- JSON Parsing Data Classes ---
    
    data class TmdbResponse(
        @JsonProperty("results") val results: List<TmdbMovie>?
    )

    data class TmdbMovie(
        @JsonProperty("id") val id: Int?, 
        @JsonProperty("title") val title: String?, 
        @JsonProperty("poster_path") val poster_path: String?
    )

    data class TmdbDetails(
        @JsonProperty("title") val title: String?, 
        @JsonProperty("poster_path") val poster_path: String?, 
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("vote_average") val vote_average: Double?
    )

    data class VideoData(
        @JsonProperty("videoSource") val videoSource: String?
    )
}
