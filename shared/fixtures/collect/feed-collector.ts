// A collector written by hand for the engine tests: reads an RSS 2.0 feed with string operations (the engine has
// no DOM). Stops at the cursor (the link of the newest item of the previous run) and returns the new one.

interface Input {
  url: string;
  keywords: string[];
  cursor: string | null;
}

interface FeedRecord {
  title: string;
  link: string;
  date: string;
  rawText: string;
  media?: string[];
}

const ENTITIES: Record<string, string> = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&#39;": "'" };

function decode(text: string): string {
  const cdata = text.match(/^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/);
  if (cdata) return cdata[1]!.trim();
  return text.replace(/&(amp|lt|gt|quot|#39);/g, (entity: string): string => ENTITIES[entity] ?? entity).trim();
}

function tag(block: string, name: string): string {
  const found = block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)</${name}>`));
  return found ? decode(found[1] as string) : "";
}

const stripTags = (html: string): string => html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();

async function collect(input: Input): Promise<{ records: FeedRecord[]; cursor: string | null }> {
  const response = await fetch(input.url);
  if (!response.ok) throw new Error(`the feed answered ${response.status}`);
  const xml: string = await response.text();
  const records: FeedRecord[] = [];
  for (const item of xml.matchAll(/<item>([\s\S]*?)<\/item>/g)) {
    const block = item[1]!;
    const link = tag(block, "link");
    if (input.cursor !== null && link === input.cursor) break;
    const enclosure = block.match(/<enclosure[^>]*url="([^"]+)"/);
    records.push({
      title: tag(block, "title"),
      link,
      date: tag(block, "pubDate"),
      rawText: stripTags(tag(block, "description")),
      ...(enclosure ? { media: [enclosure[1] as string] } : {}),
    });
  }
  return { records, cursor: records.length > 0 ? records[0]!.link : input.cursor };
}
