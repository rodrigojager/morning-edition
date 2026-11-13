package dobra.ai.collector

import dobra.ai.provider.AiJob
import dobra.ai.provider.JobChoice
import dobra.ai.rewrite.Recorded
import dobra.collect.engine.FetchResponse
import dobra.collect.engine.SavedPages
import dobra.model.Magazine
import dobra.model.Source
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Saved pages, collectors and recorded AI answers for the collector-writing tests. Everything is invented, on the
 * reserved example.org domain; no test reaches a network or a live provider.
 */
object CollectorKit {
    const val URL = "https://www.example.org/noticias"

    /** The page as it was when the source was added. */
    val PAGE = """
        <html><body><main>
        <article><h2><a href="https://www.example.org/a/1">Falha de segurança no roteador</a></h2>
          <time>2026-11-12</time><p>Atualize o firmware hoje mesmo.</p></article>
        <article><h2><a href="https://www.example.org/a/2">Kotlin 2.4 sai com novidades</a></h2>
          <time>2026-11-11</time><p>O compilador ficou mais rápido.</p></article>
        <article><h2><a href="https://www.example.org/a/3">Receita de pão caseiro</a></h2>
          <time>2026-11-10</time><p>Farinha, água e paciência.</p></article>
        </main></body></html>
    """.trimIndent()

    /** The same site after a redesign: the items moved to a list, so the first collector finds nothing. */
    val REDESIGNED = """
        <html><body><ul class="feed">
        <li class="item"><a class="t" href="https://www.example.org/b/9">Nova falha de segurança em câmeras</a>
          <span class="d">2026-11-20</span><span class="x">Fabricante promete correção.</span></li>
        <li class="item"><a class="t" href="https://www.example.org/b/8">Kotlin ganha novo depurador</a>
          <span class="d">2026-11-19</span><span class="x">Integração com a IDE.</span></li>
        </ul></body></html>
    """.trimIndent()

    /** A collector that reads [PAGE]. */
    val ARTICLES = """
        type Rec = { title: string; link: string; date: string; rawText: string };

        async function collect(input: { url: string; keywords: string[]; cursor: string | null }) {
          const page: string = await (await fetch(input.url)).text();
          const records: Rec[] = [];
          for (const chunk of page.split("<article").slice(1)) {
            const a = chunk.match(/<a href="([^"]+)">([^<]+)<\/a>/);
            if (!a) continue;
            const time = chunk.match(/<time>([^<]*)<\/time>/);
            const text = chunk.match(/<p>([^<]*)<\/p>/);
            records.push({ title: a[2]!, link: a[1]!, date: time ? time[1]! : "", rawText: text ? text[1]! : "" });
          }
          return { records, cursor: null };
        }
    """.trimIndent()

    /** A collector that forgets the link of every record: refused by the acceptance run. */
    val NO_LINKS = """
        async function collect(input: { url: string }) {
          const page: string = await (await fetch(input.url)).text();
          const titles = page.match(/<h2>[\s\S]*?<\/h2>/g) || [];
          return { records: titles.map((t: string) => ({ title: t.replace(/<[^>]+>/g, "").trim(), date: "", rawText: "" })), cursor: null };
        }
    """.trimIndent()

    /** A collector that reads a page the saved copy does not have. */
    val WRONG_PAGE = """
        async function collect(input: { url: string }) {
          const response = await fetch("/feed.xml");
          if (!response.ok) throw new Error("the feed answered " + response.status);
          return { records: [], cursor: null };
        }
    """.trimIndent()

    /** The collector adapted to [REDESIGNED]. */
    val LIST_ITEMS = """
        async function collect(input: { url: string; keywords: string[]; cursor: string | null }) {
          const page: string = await (await fetch(input.url)).text();
          const records = [];
          for (const chunk of page.split('<li class="item">').slice(1)) {
            const a = chunk.match(/<a class="t" href="([^"]+)">([^<]+)<\/a>/);
            if (!a) continue;
            const d = chunk.match(/<span class="d">([^<]*)<\/span>/);
            const x = chunk.match(/<span class="x">([^<]*)<\/span>/);
            records.push({ title: a[2], link: a[1], date: d ? d[1] : "", rawText: x ? x[1] : "" });
          }
          return { records, cursor: null };
        }
    """.trimIndent()

    /** An answer of the collector-writing job, as the prompt asks for it. */
    fun answer(script: String, keywords: List<String> = emptyList()): String = buildJsonObject {
        put("script", script)
        putJsonArray("keywords") { keywords.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    /** Recorded answers replayed through the gateway, with a provider and model chosen for writing collectors. */
    fun ai(vararg answers: String): Recorded = Recorded(answers.toList()).also { r ->
        r.registry.choose(AiJob.WRITE_COLLECTORS, JobChoice(r.registry.state.value.providers.first().id, "model-collectors"))
    }

    /** The site: serves [page] at [URL]. */
    fun site(page: String = PAGE, status: Int = 200) = SavedPages(mapOf(URL to FetchResponse(status, page, "text/html", URL)))

    fun magazine(source: Source = Source("noticias", "Notícias", URL, liked = "falhas de segurança e Kotlin")) =
        Magazine(id = "tec", name = "Tecnologia", keywords = listOf("segurança"), sources = listOf(source))
}
