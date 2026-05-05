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
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        if (page > 1) return newHomePageResponse(emptyList())

        // Updated with your scraped working IDs
        val previewIds = listOf("1137861", "69537", "927342", "949229", "1136423", "263471", "833324", "370076", "148284", "281394")
        val tamilIds = listOf("1211999", "280951", "949380", "1351991", "1114668", "1323267", "1386625", "922360", "622792", "1311073")
        val dubbedIds = listOf("1579", "157336", "7451", "502356", "24428", "299536", "278", "634649", "238", "299534")

        suspend fun getMoviesFromIds(ids: List<String>): List<SearchResponse> {
            return coroutineScope {
                ids.map { id ->
                    async {
                        val reqUrl = "$TMDB_API/movie/$id?api_key=$TMDB_KEY&language=ta-IN"
                        val details = app.get(reqUrl).parsedSafe<TmdbDetails>() ?: return@async null
                        
                        newMovieSearchResponse(
                            name = details.title ?: "",
                            url = "$mainUrl/$id",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = details.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

        val previewMovies = getMoviesFromIds(previewIds)
        val tamilMovies = getMoviesFromIds(tamilIds)
        val dubbedMovies = getMoviesFromIds(dubbedIds)

        return newHomePageResponse(
            listOf(
                HomePageList("Featured Previews", previewMovies, isHorizontalImages = true),
                HomePageList("Latest Tamil Movies", tamilMovies),
                HomePageList("Tamil Dubbed Movies", dubbedMovies)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$TMDB_API/search/movie?api_key=$TMDB_KEY&with_original_language=ta&query=$query"
        val response = app.get(url).parsedSafe<TmdbResponse>()

        // Search still uses background checks because the user input is dynamic
        return coroutineScope {
            response?.results?.take(15)?.map { movie ->
                async {
                    val title = movie.title ?: return@async null
                    val id = movie.id?.toString() ?: return@async null
                    
                    val isAvailable = try {
                        val res = app.get("$HOST/tamil/tmdb/$id")
                        res.text.contains("FirePlayer")
                    } catch (e: Exception) {
                        false
                    }

                    if (isAvailable) {
                        newMovieSearchResponse(
                            name = title,
                            url = "$mainUrl/$id",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        }
                    } else {
                        null
                    }
                }
            }?.awaitAll()?.filterNotNull() ?: emptyList()
        }
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
