package com.samir.lumiplay

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

data class AnimeSaltItem(
    val title: String,
    val slug: String,
    val type: String,
    val poster: String,
    val url: String
)

data class AnimeSaltEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val slug: String,
    val url: String
)

data class AnimeSaltDetails(
    val slug: String,
    val title: String,
    val poster: String,
    val overview: String,
    val seasons: Map<Int, List<AnimeSaltEpisode>>
)

data class AnimeSaltServer(
    val name: String,
    val language: String = "",
    val url: String
)

enum class AnimeCategory(val label: String, val path: String) {
    HOME("Home", "/"),
    ONGOING("Ongoing", "/category/status/ongoing/"),
    SERIES("Series", "/category/type/anime/?type=series"),
    MOVIES("Movies", "/category/type/anime/?type=movies"),
    CRUNCHYROLL("Crunchyroll", "/category/network/crunchyroll/"),
    NETFLIX("Netflix", "/category/network/netflix/"),
    CARTOON("Cartoon", "/category/type/cartoon/?type=series")
}

object AnimeSaltScraper {
    const val BASE_URL = "https://animesalt.cx"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private fun getDoc(url: String): Document {
        return Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .referrer("$BASE_URL/")
            .timeout(15000)
            .get()
    }

    private fun fetchSeasonAjax(postId: String, seasonNum: Int, refererUrl: String): Document? {
        return try {
            Jsoup.connect("$BASE_URL/wp-admin/admin-ajax.php")
                .userAgent(USER_AGENT)
                .referrer(refererUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .data("action", "action_select_season")
                .data("season", seasonNum.toString())
                .data("post", postId)
                .method(Connection.Method.POST)
                .ignoreContentType(true)
                .timeout(15000)
                .post()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSlug(url: String): String {
        return url.trimEnd('/').substringAfterLast('/', "")
    }

    private fun parseCards(doc: Document): List<AnimeSaltItem> {
        val items = mutableListOf<AnimeSaltItem>()
        val seen = mutableSetOf<String>()

        val elements = doc.select("article.post, li.post, .post-lst article, .post-lst li")
        for (el in elements) {
            val linkEl = el.selectFirst("a[href]") ?: continue
            val url = linkEl.absUrl("href").ifBlank { linkEl.attr("href") }
            if (url.isBlank() || !seen.add(url)) continue

            val title = el.selectFirst(".entry-title, h2, h3, .title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: linkEl.attr("title").trim()
            if (title.isBlank()) continue

            val imgEl = el.selectFirst("img")
            var poster = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src").orEmpty()
            if (poster.startsWith("//")) poster = "https:$poster"

            val type = when {
                url.contains("/episode/") -> "episode"
                url.contains("/movies/") -> "movie"
                else -> "series"
            }

            items.add(
                AnimeSaltItem(
                    title = title,
                    slug = extractSlug(url),
                    type = type,
                    poster = poster,
                    url = url
                )
            )
        }
        return items
    }

    suspend fun fetchCategory(category: AnimeCategory): List<AnimeSaltItem> =
        withContext(Dispatchers.IO) {
            parseCards(getDoc("$BASE_URL${category.path}"))
        }

    suspend fun search(query: String): List<AnimeSaltItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        parseCards(getDoc("$BASE_URL/?s=$encoded"))
    }

    private fun parseSeasonEpisode(slug: String, rawText: String, forcedSeason: Int? = null): Pair<Int, Int> {
        val sxeRegex = Regex("(\\d+)[xX](\\d+)")
        sxeRegex.find(slug)?.let {
            val s = forcedSeason ?: (it.groupValues[1].toIntOrNull() ?: 1)
            val e = it.groupValues[2].toIntOrNull() ?: 1
            return s to e
        }
        val seRegex = Regex("[sS](\\d+)\\s*[eE](\\d+)")
        seRegex.find(rawText)?.let {
            val s = forcedSeason ?: (it.groupValues[1].toIntOrNull() ?: 1)
            val e = it.groupValues[2].toIntOrNull() ?: 1
            return s to e
        }
        val numRegex = Regex("(\\d+)$")
        val ep = numRegex.find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return (forcedSeason ?: 1) to ep
    }

    private fun collectEpisodesFromDoc(
        doc: Document,
        epMap: MutableMap<Pair<Int, Int>, AnimeSaltEpisode>,
        forcedSeason: Int? = null
    ) {
        for (el in doc.select("a[href*='/episode/']")) {
            val epUrl = el.absUrl("href").ifBlank { el.attr("href") }
            if (epUrl.isBlank()) continue
            val epSlug = extractSlug(epUrl)
            val rawText = el.text().trim().replace(Regex("\\s+"), " ")
            val (season, episode) = parseSeasonEpisode(epSlug, rawText, forcedSeason)
            val key = season to episode
            if (!epMap.containsKey(key)) {
                epMap[key] = AnimeSaltEpisode(
                    season = season,
                    episode = episode,
                    title = "Episode $episode",
                    slug = epSlug,
                    url = epUrl
                )
            }
        }
    }

    suspend fun fetchDetails(itemUrl: String, fallbackSlug: String): AnimeSaltDetails =
        withContext(Dispatchers.IO) {
            val doc = getDoc(itemUrl)
            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim().orEmpty()
            val overview = doc.selectFirst(".description p, .entry-content p")?.text()?.trim().orEmpty()
            val imgEl = doc.selectFirst(".post-thumbnail img, .poster img")
            var poster = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src").orEmpty()
            if (poster.startsWith("//")) poster = "https:$poster"

            val epMap = linkedMapOf<Pair<Int, Int>, AnimeSaltEpisode>()
            val seasonBtns = doc.select(".season-btn[data-post][data-season]")

            if (seasonBtns.isNotEmpty()) {
                for (btn in seasonBtns) {
                    val postId = btn.attr("data-post").trim()
                    val seasonNum = btn.attr("data-season").trim().toIntOrNull() ?: continue
                    if (postId.isBlank()) continue
                    val ajaxDoc = fetchSeasonAjax(postId, seasonNum, itemUrl)
                    if (ajaxDoc != null) {
                        collectEpisodesFromDoc(ajaxDoc, epMap, forcedSeason = seasonNum)
                    }
                }
            }

            if (epMap.isEmpty()) {
                collectEpisodesFromDoc(doc, epMap)
            }

            val grouped = epMap.values
                .sortedWith(compareBy<AnimeSaltEpisode> { it.season }.thenBy { it.episode })
                .groupBy { it.season }
                .toSortedMap()

            AnimeSaltDetails(
                slug = fallbackSlug,
                title = title,
                poster = poster,
                overview = overview,
                seasons = grouped
            )
        }

    suspend fun fetchEpisodeStreams(episodeUrl: String): List<AnimeSaltServer> =
        withContext(Dispatchers.IO) {
            val doc = getDoc(episodeUrl)
            val servers = mutableListOf<AnimeSaltServer>()
            val iframes = doc.select("#aa-options iframe, .video-player iframe")

            iframes.forEachIndexed { idx, el ->
                val rawUrl = el.attr("data-src").takeIf { it.isNotBlank() }
                    ?: el.attr("src").orEmpty()
                if (rawUrl.isBlank()) return@forEachIndexed

                if (rawUrl.contains("player.php?data=")) {
                    try {
                        val encoded = Uri.parse(rawUrl).getQueryParameter("data")
                        if (!encoded.isNullOrBlank()) {
                            val decodedBytes = Base64.decode(encoded, Base64.DEFAULT)
                            val jsonArray = JSONArray(String(decodedBytes, Charsets.UTF_8))
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                val lang = obj.optString("language", "Audio")
                                val link = obj.optString("link", "")
                                if (link.isNotBlank()) {
                                    servers.add(
                                        AnimeSaltServer(
                                            name = lang,
                                            language = lang,
                                            url = link
                                        )
                                    )
                                }
                            }
                            return@forEachIndexed
                        }
                    } catch (_: Exception) {
                    }
                }

                val name = when {
                    rawUrl.contains("ravok", ignoreCase = true) -> "Ravok (Multi)"
                    rawUrl.contains("cybervynx", ignoreCase = true) -> "Cybervynx"
                    else -> "Server ${idx + 1}"
                }
                servers.add(AnimeSaltServer(name = name, url = rawUrl))
            }
            servers
        }
}
