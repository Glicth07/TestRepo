package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z3.idlixku.com"
    private val apiBase = "$mainUrl/api"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "featured" to "Featured",
        "trending_movies" to "Trending Movies",
        "trending_series" to "Trending TV Series",
        "movies" to "Movie",
        "series" to "TV Series",
        "network:prime-video" to "Amazon Prime",
        "network:apple-tv-plus" to "Apple TV+ Series",
        "network:disney-plus" to "Disney+ Series",
        "network:hbo" to "HBO Series",
        "network:netflix" to "Netflix Series",
    )

    private fun posterUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return "https://image.tmdb.org/t/p/w500$path"
    }

    private fun contentTypeToTvType(contentType: String?): TvType {
        return if (contentType == "tv_series" || contentType == "series") TvType.TvSeries else TvType.Movie
    }

    private fun IdlixContent.toSearchResponse(): SearchResponse? {
        val title = title ?: return null
        val slug = slug ?: return null
        val tvType = contentTypeToTvType(contentType)
        val href = if (tvType == TvType.TvSeries) "$mainUrl/series/$slug" else "$mainUrl/movie/$slug"
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl(posterPath)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl(posterPath)
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = when {
            request.data == "featured" || request.data == "movies" ->
                "$apiBase/movies?page=$page&limit=36&sort=createdAt"
            request.data == "series" ->
                "$apiBase/series?page=$page&limit=36&sort=createdAt"
            request.data == "trending_movies" ->
                "$apiBase/trending/top?limit=36&period=7d&contentType=movie"
            request.data == "trending_series" ->
                "$apiBase/trending/top?limit=36&period=7d&contentType=tv_series"
            request.data.startsWith("network:") ->
                "$apiBase/browse?page=$page&limit=36&sort=latest&network=${request.data.substringAfter(":")}"
            else ->
                "$apiBase/movies?page=$page&limit=36&sort=createdAt"
        }

        val home = when {
            request.data.startsWith("trending") -> {
                app.get(url).parsedSafe<IdlixTrendingResponse>()?.data?.mapNotNull { it.toSearchResponse() }
            }
            request.data.startsWith("network:") -> {
                app.get(url).parsedSafe<IdlixBrowseResponse>()?.data?.mapNotNull { it.toSearchResponse() }
            }
            else -> {
                app.get(url).parsedSafe<IdlixListResponse>()?.data?.mapNotNull { it.toSearchResponse() }
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = "$apiBase/search?q=$encoded&page=1&limit=24&order=popular"
        return app.get(url).parsedSafe<IdlixSearchResponse>()?.results?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val isSeries = url.contains("/series/")

        return if (isSeries) {
            val content = app.get("$apiBase/series/$slug").parsedSafe<IdlixContent>()
            val title = content?.title?.replace(Regex("\\(\\d{4}\\)"), "")?.trim().orEmpty()

            val poster = posterUrl(content?.posterPath) ?: posterUrl(content?.backdropPath)
            val tags = content?.genres?.mapNotNull { it.name } ?: emptyList()
            val year = (content?.firstAirDate ?: "").take(4).toIntOrNull()
            val description = content?.overview
            val rating = content?.voteAverage
            val trailer = content?.trailerUrl

            val episodes = mutableListOf<Episode>()
            content?.seasons?.forEach { season ->
                val seasonNumber = season.seasonNumber ?: return@forEach
                try {
                    val epResponse = app.get("$apiBase/series/$slug/season/$seasonNumber/episode/1")
                        .parsedSafe<IdlixEpisodeResponse>()
                    epResponse?.episodes?.forEach { ep ->
                        episodes.add(newEpisode("$mainUrl/series/$slug/season/$seasonNumber/episode/${ep.episodeNumber}") {
                            this.name = ep.name
                            this.season = seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = posterUrl(ep.stillPath)
                            this.description = ep.overview
                        })
                    }
                } catch (_: Exception) {
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it.toDoubleOrNull() ?: 0.0) }
                addTrailer(trailer)
            }
        } else {
            val content = app.get("$apiBase/movies/$slug").parsedSafe<IdlixContent>()
            val title = content?.title?.replace(Regex("\\(\\d{4}\\)"), "")?.trim().orEmpty()

            val poster = posterUrl(content?.posterPath) ?: posterUrl(content?.backdropPath)
            val tags = content?.genres?.mapNotNull { it.name } ?: emptyList()
            val year = (content?.releaseDate ?: "").take(4).toIntOrNull()
            val description = content?.overview
            val rating = content?.voteAverage
            val trailer = content?.trailerUrl

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it.toDoubleOrNull() ?: 0.0) }
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        val regex = Regex("""majorplay\.net/v/[^"'\s]+config[^"'\s]*\.json""")
        val script = """var __idlixClicked=false; var __idlixIv=setInterval(function(){ if(__idlixClicked){clearInterval(__idlixIv);return;} var b=Array.from(document.querySelectorAll('button')).find(function(x){return (x.outerHTML||'').indexOf('absolute inset-0')>=0;}); if(b){b.click();__idlixClicked=true;clearInterval(__idlixIv);} },1000);"""
        val resolver = WebViewResolver(
            regex,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            script = script,
            timeout = 45_000L
        )

        return try {
            val res = app.get(pageUrl, interceptor = resolver)
            val videoUrl = res.url
            Log.d("Idlix", "Intercepted URL: $videoUrl")
            if (videoUrl.isBlank() || !videoUrl.contains("majorplay")) return false
            loadExtractor(videoUrl, videoUrl, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    // Data classes
    data class IdlixGenre(
        @JsonProperty("name") val name: String?
    )

    data class IdlixContent(
        @JsonProperty("id") val id: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("posterPath") val posterPath: String?,
        @JsonProperty("backdropPath") val backdropPath: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("releaseDate") val releaseDate: String?,
        @JsonProperty("firstAirDate") val firstAirDate: String?,
        @JsonProperty("voteAverage") val voteAverage: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("contentType") val contentType: String?,
        @JsonProperty("numberOfSeasons") val numberOfSeasons: Int?,
        @JsonProperty("trailerUrl") val trailerUrl: String?,
        @JsonProperty("genres") val genres: List<IdlixGenre>?,
        @JsonProperty("seasons") val seasons: List<IdlixSeason>?
    )

    data class IdlixSeason(
        @JsonProperty("seasonNumber") val seasonNumber: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("episodeCount") val episodeCount: Int?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("posterPath") val posterPath: String?
    )

    data class IdlixListResponse(
        @JsonProperty("data") val data: List<IdlixContent>?,
        @JsonProperty("pagination") val pagination: Any?
    )

    data class IdlixTrendingResponse(
        @JsonProperty("data") val data: List<IdlixContent>?
    )

    data class IdlixBrowseResponse(
        @JsonProperty("data") val data: List<IdlixContent>?
    )

    data class IdlixSearchResponse(
        @JsonProperty("results") val results: List<IdlixContent>?,
        @JsonProperty("total") val total: Int?
    )

    data class IdlixEpisode(
        @JsonProperty("id") val id: String?,
        @JsonProperty("episodeNumber") val episodeNumber: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("stillPath") val stillPath: String?,
        @JsonProperty("airDate") val airDate: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("voteAverage") val voteAverage: String?
    )

    data class IdlixEpisodeResponse(
        @JsonProperty("series") val series: IdlixContent?,
        @JsonProperty("season") val season: IdlixSeason?,
        @JsonProperty("episode") val episode: IdlixEpisode?,
        @JsonProperty("episodes") val episodes: List<IdlixEpisode>?
    )
}
