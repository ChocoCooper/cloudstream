package com.Tamilian

// --- Exact explicit imports needed for MainAPI ---
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
        
        // Using api.tmdb.org to bypass Indian ISP blocks on api.themoviedb.org
        const val TMDB_API = "https://api.tmdb.org/3"
        
        // IMPORTANT: Replace this with your actual TMDB API Key from developer.themoviedb.org
        const val TMDB_KEY = "fb7bb23f03b6994dafc674c074d01761" 
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$TMDB_API/movie/popular?api_key=$TMDB_KEY&language=ta-IN&page=$page"
        val response = app.get(url).parsedSafe<TmdbResponse>()

        val homeItems = response?.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            
            newMovieSearchResponse(
                name = title,
                url = id, // We pass the TMDB ID forward as the 'url'
                type = TvType.Movie
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.poster_path}"
            }
        } ?: listOf()

        return newHomePageResponse("Popular Tamil Movies", homeItems)
    }

    override suspend fun load(url: String): LoadResponse? {
        // The 'url' passed here is the TMDB ID we set in getMainPage
        val tmdbId = url 
        val reqUrl = "$TMDB_API/movie/$tmdbId?api_key=$TMDB_KEY&language=ta-IN"
        
        val details = app.get(reqUrl).parsedSafe<TmdbDetails>() ?: return null

        return newMovieLoadResponse(
            name = details.title ?: "",
            url = url,
            type = TvType.Movie,
            dataUrl = tmdbId // Pass the TMDB ID forward to loadLinks
        ) {
            this.posterUrl = "https://image.tmdb.org/t/p/w500${details.poster_path}"
            this.plot = details.overview
            this.year = details.release_date?.split("-")?.firstOrNull()?.toIntOrNull()
            this.rating = details.vote_average?.times(10)?.toInt()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' contains the TMDB ID passed from the load() function
        val tmdbId = data 
        
        val script = app.get("$HOST/tamil/tmdb/$tmdbId")
            .document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()?.let { getAndUnpack(it) }

        val token = script?.substringAfter("FirePlayer(\"")?.substringBefore("\",")
        val m3u8 = app.post("$HOST/player/index.php?data=$token&do=getVideo", headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
            .parsedSafe<VideoData>()
            
        val headers = mapOf("Origin" to HOST)
        
        m3u8?.let {
            safeApiCall {
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = it.videoSource,
                        type = ExtractorLinkType.M3U8,
                        quality = Qualities.P1080.value
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = headers
                    }
                )
            }
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
        @JsonProperty("videoSource") val videoSource: String
    )
}
