package eu.kanade.tachiyomi.extension.pt.yugenmangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class YugenMangas : HttpSource() {

    override val name = "Yugen Mangás"

    override val baseUrl = "https://yugenmangas.net.br"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1, 2, TimeUnit.SECONDS)
        .build()

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    val apiHeaders by lazy { apiHeadersBuilder().build() }

    private fun apiHeadersBuilder(): Headers.Builder = headersBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Origin", baseUrl)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-site")

    override fun popularMangaRequest(page: Int): Request {
        return GET("$API_BASE_URL/top_series_all/", apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<List<YugenMangaDto>>()
        val mangaList = result.map { it.toSManga(API_HOST) }
        return MangasPage(mangaList, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$API_BASE_URL/latest_updates/", apiHeaders)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val apiUrl = "$API_BASE_URL/series".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .build()

        return GET(apiUrl, apiHeaders)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/series/")
        return POST("$API_BASE_URL/serie/serie_details/$slug", apiHeaders)
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        return response.parseAs<YugenMangaDto>().toSManga(API_BASE_URL)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/series/")
        val body = YugenGetChaptersBySeriesDto(slug)
        val payload = json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = apiHeadersBuilder()
            .set("Content-Length", payload.contentLength().toString())
            .set("Content-Type", payload.contentType().toString())
            .build()

        return POST("$API_BASE_URL/chapters/get_chapters_by_serie/", newHeaders, payload)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val (seriesSlug) = response.request.body!!.parseAs<YugenGetChaptersBySeriesDto>()

        return response.parseAs<YugenChapterListDto>().chapters
            .map { it.toSChapter(seriesSlug) }
            .sortedByDescending(SChapter::chapter_number)
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun pageListParse(response: Response): List<Page> {
        val json = response.asJsoup().selectFirst("script#__NUXT_DATA__")!!.data()
        return CHAPTER_PAGES_REGEX.findAll(json)
            .mapIndexed { index, image ->
                Page(index, baseUrl, "$API_HOST/${image.value}")
            }
            .toList()
    }

    override fun imageUrlParse(response: Response) = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromString(it.body.string())
    }

    private inline fun <reified T> RequestBody.parseAs(): T {
        val jsonString = Buffer().also { writeTo(it) }.readUtf8()
        return json.decodeFromString(jsonString)
    }

    companion object {
        private const val API_HOST = "https://api.yugenmangas.net.br"
        private const val API_BASE_URL = "$API_HOST/api"
        private val CHAPTER_PAGES_REGEX = """(media/series[^"]+)""".toRegex()
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
