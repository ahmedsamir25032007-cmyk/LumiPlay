package com.samir.lumiplay

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    val title: String,
    val slug: String,
    val url: String
)

data class AnimeSaltDetails(
    val slug: String,
    val title: String,
    val poster: String,
    val overview: String,
    val episodes: List<AnimeSaltEpisode>
)

data class AnimeSaltServer(
    val name: String,
    val language: String = "",
    val url: String
)

object AnimeSaltScraper {
    private const val BASE_URL = "https://animesalt.cx"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private fun getDoc(url: String): Document {
        return Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .referrer("$BASE_URL/")
            .timeout(15000)
            .get()
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

    suspend fun fetchHome(): List<AnimeSaltItem> = withContext(Dispatchers.IO) {
        parseCards(getDoc(BASE_URL))
    }

    suspend fun search(query: String): List<AnimeSaltItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        parseCards(getDoc("$BASE_URL/?s=$encoded"))
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

            val episodes = mutableListOf<AnimeSaltEpisode>()
            val seenEp = mutableSetOf<String>()
            for (el in doc.select("a[href*='/episode/']")) {
                val epUrl = el.absUrl("href").ifBlank { el.attr("href") }
                if (epUrl.isBlank() || !seenEp.add(epUrl)) continue
                val epSlug = extractSlug(epUrl)
                val epTitle = el.text().trim().replace(Regex("\\s+"), " ").ifBlank { epSlug }
                episodes.add(AnimeSaltEpisode(title = epTitle, slug = epSlug, url = epUrl))
            }

            AnimeSaltDetails(
                slug = fallbackSlug,
                title = title,
                poster = poster,
                overview = overview,
                episodes = episodes
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
                                            name = "Abyss ($lang)",
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
                    rawUrl.contains("ravok", ignoreCase = true) -> "Ravok (Multi/Auto)"
                    rawUrl.contains("cybervynx", ignoreCase = true) -> "Cybervynx"
                    else -> "Server ${idx + 1}"
                }
                servers.add(AnimeSaltServer(name = name, url = rawUrl))
            }
            servers
        }
}
