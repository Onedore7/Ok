package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://oploverz.care"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val acefile = "https://acefile.co"
        const val lbx = "https://lbx.to"
        const val linkbox = "https://www.linkbox.to"

        fun getType(t: String): TvType {
            return when {
                t.contains("TV") -> TvType.Anime
                t.contains("Movie") -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Episode Terbaru",
        "&status=&type=&order=latest" to "Anime Terbaru",
        "&sub=&order=popular" to "Popular Anime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/anime/?page=$page${request.data}").document
        val home = document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {

        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-ova")) -> Regex("(.+)-episode").find(
                    title
                )?.groupValues?.get(1).toString()
                (title.contains("-ova")) -> Regex("(.+)-ova").find(title)?.groupValues?.get(1)
                    .toString()
                (title.contains("-movie")) -> Regex("(.+)-subtitle").find(title)?.groupValues?.get(1)
                    .toString()
                else -> Regex("(.+)-subtitle").find(title)?.groupValues?.get(1).toString()
                    .replace(Regex("-\\d+"), "")
            }

            when {
                title.contains("overlord") -> {
                    title = title.replace("s", "season-")
                }
                title.contains("kaguya-sama") -> {
                    title = title.replace("s3", "ultra-romantic")
                }
            }

            "$mainUrl/anime/$title"
        }

    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(this.selectFirst("a.tip")!!.attr("href"))
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val type = getType(this.selectFirst(".eggtype, .typez")?.text()?.trim().toString())

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document

        return document.select("article[itemscope=itemscope]").map {
            val title = it.selectFirst(".tt")?.ownText()?.trim().toString()
            val poster = fixUrlNull(it.selectFirst("img")?.attr("src"))
            val tvType = getType(it.selectFirst(".typez")?.text().toString())
            val href = fixUrl(it.selectFirst("a.tip")!!.attr("href"))

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")!!.text().trim()
        val poster = document.select(".thumb > img").attr("src")
        val tags = document.select(".genxed > a").map { it.text() }

        val year = Regex("\\d, (\\d*)").find(
            document.selectFirst(".info-content > .spe > span > time")!!.text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val status = getStatus(
            document.select(".info-content > .spe > span:nth-child(1)")
                .text().trim().replace("Status: ", "")
        )
        val typeCheck =
            when (document.select(".info-content > .spe > span:nth-child(5), .info-content > .spe > span")
                .text().trim()) {
                "OVA" -> "OVA"
                "Movie" -> "Movie"
                else -> "TV"
            }
        val description = document.select(".entry-content > p").text().trim()
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val episodes = document.select(".eplister > ul > li").map {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select(".epl-title").text()
            val episode = Regex("Episode\\s?(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            Episode(link, name, episode = episode)
        }.reversed()

        val recommendations =
            document.select(".listupd > article[itemscope=itemscope]").mapNotNull { rec ->
                val epTitle = rec.selectFirst(".tt")!!.ownText().trim()
                val epPoster = rec.selectFirst("img")!!.attr("src")
                val epType = getType(rec.selectFirst(".typez")?.text().toString())
                val epHref = fixUrl(rec.selectFirst("a.tip")!!.attr("href"))

                newAnimeSearchResponse(epTitle, epHref, epType) {
                    this.posterUrl = epPoster
                    addDubStatus(dubExist = false, subExist = true)
                }
            }

        return newAnimeLoadResponse(title, url, getType(typeCheck)) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addTrailer(trailer)
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = mutableListOf<Pair<String?, String>>()
        val streamingSources = document.select(".mobius > .mirror > option").mapNotNull {
            "" to fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src"))
        }
        if (streamingSources.isNotEmpty()) sources.addAll(streamingSources)
        val downloadSources =
            document.select("div.mctnx div.soraurlx").mapNotNull { item ->
                item.select("a").map { item.select("strong").text() to it.attr("href") }
            }.flatten()
        if (downloadSources.isNotEmpty()) sources.addAll(downloadSources)

        sources.filter { it.second.startsWith("https") }.
        apmap { (quality, source) ->
            val video = fixedIframe(source)
            val videoQuality = getQualityFromName(quality)
            if(video.endsWith(".mp4") || video.endsWith(".mkv")) {
                callback.invoke(
                    ExtractorLink(
                        "Direct",
                        "Direct",
                        video,
                        "",
                        videoQuality
                    )
                )
            } else {
                loadExtractor(video, data, subtitleCallback) { link ->
                    callback.invoke(
                        ExtractorLink(
                            link.name,
                            link.name,
                            link.url,
                            link.referer,
                            videoQuality,
                            link.isM3u8,
                            link.headers,
                            link.extractorData
                        )
                    )
                }
            }
        }

        return true
    }

    private suspend fun fixedIframe(url: String): String {
        val id = Regex("""(?:/f/|/file/)(\w+)""").find(url)?.groupValues?.getOrNull(1)
        return when {
            url.startsWith(acefile) -> "$acefile/player/$id"
            url.startsWith(lbx) -> {
                val itemId = app.get("$linkbox/api/file/share_out_list/?sortField=utime&sortAsc=0&pageNo=1&pageSize=50&shareToken=$id&scene=singleItem&needTpInfo=1&lan=en").parsedSafe<Responses>()?.data?.itemId
                "$linkbox/a/f/$itemId"
            }
            else -> url
        }
    }

    data class RList(
        @JsonProperty("url") val url: String,
        @JsonProperty("resolution") val resolution: String?,
    )

    data class Data(
        @JsonProperty("rList") val rList: List<RList>? = arrayListOf(),
        @JsonProperty("itemId") val itemId: String? = null,
    )

    data class Responses(
        @JsonProperty("data") val data: Data?,
    )

}

class Streamhide : Filesim() {
    override val mainUrl = "https://streamhide.to"
    override val name = "Streamhide"
}

open class Pixeldrain : ExtractorApi() {
    override val name = "Pixeldrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mId = Regex("/([ul]/[\\da-zA-Z\\-]+)").find(url)?.groupValues?.get(1)?.split("/")
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "$mainUrl/api/file/${mId?.last() ?: return}?download",
                url,
                Qualities.Unknown.value,
            )
        )
    }

}
