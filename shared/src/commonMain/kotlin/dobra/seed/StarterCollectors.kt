package dobra.seed

import dobra.model.CollectorInfo
import dobra.model.Magazine
import dobra.model.SourceHealth

/** Hand-written collectors shipped with the starter recipe; no AI call is needed to install them. */
internal object StarterCollectors {
    fun attach(magazine: Magazine): Magazine = magazine.copy(sources = magazine.sources.map { source ->
        val script = scripts[source.id]
        if (source.collector != null || script == null) source else source.copy(
            collector = CollectorInfo(script, version = 1, writtenAt = "2026-11-13", model = "bundled"),
            lastRun = null, cursor = null,
            // The saved Apiiro page is a JavaScript shell; do not present that collector as working.
            health = if (source.id == "blog-da-apiiro") SourceHealth.REPAIRING else SourceHealth.WORKING,
        )
    })

    private val helpers = """
        function decode(s) {
          return String(s || '').replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_, t) => t)
            .replace(/&#(x[0-9a-f]+|[0-9]+);/gi, (_, n) => String.fromCodePoint(n[0].toLowerCase() === 'x' ? parseInt(n.slice(1),16) : +n))
            .replace(/&(?:amp|lt|gt|quot|apos|#39);/g, e => ({'&amp;':'&','&lt;':'<','&gt;':'>','&quot;':'"','&apos;':"'",'&#39;':"'"}[e]));
        }
        function text(s) { return decode(s).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim(); }
        function tag(s, name) {
          const m = s.match(new RegExp('<'+name+'(?:\\s[^>]*)?>([\\s\\S]*?)</'+name+'>', 'i'));
          return m ? decode(m[1]).trim() : '';
        }
        async function load(url) {
          const r = await fetch(url);
          if (!r.ok) throw new Error('HTTP '+r.status+' for '+url);
          return await r.text();
        }
        function result(records) {
          const seen = new Set();
          return { records: records.filter(r => r.title && /^https?:\/\//.test(r.link) && !seen.has(r.link) && seen.add(r.link)) };
        }
        function feed(xml) {
          if (!/<(?:rss|feed)\b/i.test(xml)) throw new Error('Expected an RSS or Atom feed');
          const records = [];
          for (const m of xml.matchAll(/<(item|entry)\b[^>]*>([\s\S]*?)<\/\1>/gi)) {
            const b = m[2];
            const links = Array.from(b.matchAll(/<link\b([^>]*)\/?\s*>/gi));
            const alt = links.find(m => !/rel=["'](?:self|enclosure)["']/.test(m[1]) && /href=/.test(m[1]));
            const attr = alt && alt[1].match(/href=["']([^"']+)["']/i);
            let link = attr ? decode(attr[1]) : tag(b, 'link');
            const body = tag(b, 'content:encoded') || tag(b, 'content') || tag(b, 'description') || tag(b, 'summary');
            // Reddit exposes the primary article as [link] inside its escaped content.
            const primary = body.match(/<a\b[^>]*href=["']([^"']+)["'][^>]*>\[link\]<\/a>/i);
            if (primary) link = decode(primary[1]);
            records.push({ title:text(tag(b,'title')), link, date:tag(b,'pubDate') || tag(b,'published') || tag(b,'updated'), rawText:text(body).slice(0,12000) });
          }
          return records;
        }
    """.trimIndent()

    private fun script(body: String) = helpers + "\n" + body.trimIndent()
    private fun rss(path: String) = script("""
        async function collect(input) { return result(feed(await load('$path'))); }
    """)

    private val scripts = mapOf(
        "github-trending" to script("""
            async function collect(input) {
              const html = await load(input.url), records = [];
              for (const m of html.matchAll(/<article\b[^>]*class="[^"]*Box-row[^"]*"[^>]*>([\s\S]*?)<\/article>/g)) {
                const b=m[1], h=b.match(/<h2\b[^>]*>([\s\S]*?)<\/h2>/), a=h && h[1].match(/href="(\/[^"?#]+\/[^"?#]+)"/);
                if (!a) continue;
                const p=b.match(/<p\b[^>]*>([\s\S]*?)<\/p>/);
                records.push({title:a[1].slice(1),link:'https://github.com'+a[1],date:'',rawText:p?text(p[1]):''});
              }
              if (!records.length) throw new Error('GitHub Trending returned no repository cards');
              return result(records);
            }
        """),
        "hacker-news" to rss("https://news.ycombinator.com/rss"),
        "lobsters" to script("""
            async function collect(input) {
              const items=JSON.parse(await load('https://lobste.rs/hottest.json'));
              return result(items.map(r => ({title:r.title,link:r.url || r.comments_url,date:r.created_at || '',rawText:text(r.description_plain || r.description || '')+' '+(r.tags || []).join(' '),signal:r.score})));
            }
        """),
        "dev" to script("""
            async function collect(input) {
              const items=JSON.parse(await load('https://dev.to/api/articles?top=7&per_page=40'));
              return result(items.map(r => ({title:r.title,link:r.url,date:r.published_at || '',rawText:text(r.description)+' '+(r.tag_list || []).join(' '),signal:r.public_reactions_count})));
            }
        """),
        "reddit-rss" to script("""
            async function collect(input) {
              const records=[]; let failures=0;
              for (const name of ['programming','selfhosted','LocalLLaMA']) {
                try { records.push(...feed(await load('https://www.reddit.com/r/'+name+'/.rss'))); }
                catch(e) { failures++; }
              }
              if (failures===3) throw new Error('All three Reddit feeds failed; inspect the HTTP results');
              return result(records);
            }
        """),
        "reddit-via-hackertab" to script("""
            async function collect(input) {
              const items=JSON.parse(await load(input.url));
              return result(items.map(r => ({title:r.title,link:r.canonical_url || r.url,date:r.published_at ? new Date(r.published_at).toISOString() : '',rawText:text(r.description)+' '+(r.tags || []).join(' '),signal:r.points_count})));
            }
        """),
        "product-hunt" to rss("https://www.producthunt.com/feed"),
        "foruns" to rss("https://discuss.python.org/top.rss?period=weekly"),
        "net-blog" to rss("https://devblogs.microsoft.com/dotnet/feed/"),
        "blog-da-apiiro" to script("""
            async function collect(input) {
              const html=await load('https://apiiro.com/blog'), records=[];
              for (const m of html.matchAll(/<a\b[^>]*href=["']((?:https:\/\/apiiro.com)?\/blog\/[^"'#?]+)["'][^>]*>([\s\S]*?)<\/a>/g)) {
                const title=text(m[2]);
                if (title) records.push({title,link:m[1].startsWith('/')?'https://apiiro.com'+m[1]:m[1],date:'',rawText:title});
              }
              if (!records.length) throw new Error('Apiiro returned no readable article links; its JavaScript page is unavailable to this collector');
              return result(records);
            }
        """),
    )
}
