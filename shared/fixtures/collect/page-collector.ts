// A collector written by hand for the engine tests: reads a news page and the page its "next" link points to.
// The engine has no DOM and no URL class, so the page is read with string operations and links are made absolute
// against the source's origin.

type PageRecord = {
  title: string;
  link: string;
  date: string;
  rawText: string;
  signal?: number;
  media?: string[];
};

const MAX_PAGES = 2;

function plain(html: string): string {
  return html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function absolute(href: string, origin: string): string {
  return /^https?:\/\//.test(href) ? href : origin + (href.startsWith("/") ? href : "/" + href);
}

function readArticles(page: string, origin: string): PageRecord[] {
  const found: PageRecord[] = [];
  for (const chunk of page.split("<article").slice(1)) {
    const body = chunk.slice(0, chunk.indexOf("</article>"));
    const anchor = body.match(/<h2[^>]*>\s*<a href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/);
    if (!anchor) continue;
    const time = body.match(/<time datetime="([^"]+)"/);
    const points = chunk.match(/data-points="(\d+)"/);
    const summary = body.match(/<p class="summary">([\s\S]*?)<\/p>/);
    const image = body.match(/<img src="([^"]+)"/);
    const record: PageRecord = {
      title: plain(anchor[2]!),
      link: absolute(anchor[1]!, origin),
      date: time ? time[1]! : "",
      rawText: summary ? plain(summary[1]!) : "",
    };
    if (points) record.signal = Number(points[1]);
    if (image) record.media = [absolute(image[1]!, origin)];
    found.push(record);
  }
  return found;
}

async function collect(input: { url: string; keywords: string[]; cursor: string | null }) {
  const origin = (input.url.match(/^https?:\/\/[^/]+/) as RegExpMatchArray)[0];
  const records: PageRecord[] = [];
  let address: string | null = input.url;
  for (let pages = 0; address !== null && pages < MAX_PAGES; pages++) {
    const response = await fetch(address);
    if (!response.ok) throw new Error(`${address} answered ${response.status}`);
    const page = await response.text();
    records.push(...readArticles(page, origin));
    const next = page.match(/<a rel="next" href="([^"]+)"/);
    address = next ? absolute(next[1]!, origin) : null;
  }
  return { records, cursor: null };
}
