import { Fragment, type ReactNode } from 'react';

/**
 * A small Markdown renderer for the strategy page: headings (#, ##, ###), paragraphs, bullet and
 * numbered lists (nested by two spaces), **bold**, *italic* and [links](https://...). It builds React
 * elements, never HTML strings, so text cannot inject markup.
 */
export function Markdown({ text, appLink }: { text: string; appLink?: AppLink }) {
  return <div className="markdown">{blocks(text.replace(/\r\n/g, '\n').split('\n'), appLink)}</div>;
}

/** Renders a link to a page inside the app ([label](/accounts/3)); without it, app paths become plain links. */
export type AppLink = (path: string, label: string, key: number) => ReactNode;

type Item = { indent: number; ordered: boolean; text: string };

function blocks(lines: string[], appLink?: AppLink): ReactNode[] {
  const out: ReactNode[] = [];
  let para: string[] = [];
  let list: Item[] = [];
  const flushPara = () => {
    if (para.length) out.push(<p key={out.length}>{inline(para.join(' '), appLink)}</p>);
    para = [];
  };
  const flushList = () => {
    if (list.length) out.push(<Fragment key={out.length}>{renderList(list, 0, list.length, appLink)}</Fragment>);
    list = [];
  };
  for (const raw of lines) {
    const heading = /^(#{1,3})\s+(.*)$/.exec(raw);
    const item = /^(\s*)([-*]|\d+[.)])\s+(.*)$/.exec(raw);
    if (heading) {
      flushPara(); flushList();
      const level = heading[1].length;
      const content = inline(heading[2], appLink);
      out.push(level === 1 ? <h2 key={out.length}>{content}</h2> : level === 2 ? <h3 key={out.length}>{content}</h3> : <h4 key={out.length}>{content}</h4>);
    } else if (item) {
      flushPara();
      const next = { indent: Math.floor(item[1].replace(/\t/g, '  ').length / 2), ordered: /\d/.test(item[2]), text: item[3] };
      // A top-level item of the other kind (bullets after numbers, or the reverse) starts a new list.
      if (next.indent === 0 && list.length && list[0].ordered !== next.ordered) flushList();
      list.push(next);
    } else if (raw.trim() === '') {
      flushPara(); flushList();
    } else if (list.length && /^\s+\S/.test(raw)) {
      list[list.length - 1].text += ' ' + raw.trim();
    } else {
      flushList();
      para.push(raw.trim());
    }
  }
  flushPara(); flushList();
  return out;
}

/** Renders items[from, to) as one list, nesting items with a deeper indent under the item above. */
function renderList(items: Item[], from: number, to: number, appLink?: AppLink): ReactNode {
  const children: ReactNode[] = [];
  let i = from;
  while (i < to) {
    const item = items[i];
    let end = i + 1;
    while (end < to && items[end].indent > item.indent) end++;
    children.push(
      <li key={i}>
        {inline(item.text, appLink)}
        {end > i + 1 && renderList(items, i + 1, end, appLink)}
      </li>,
    );
    i = end;
  }
  return items[from].ordered ? <ol>{children}</ol> : <ul>{children}</ul>;
}

function inline(text: string, appLink?: AppLink): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /\*\*([^*]+)\*\*|\*([^*]+)\*|\[([^\]]+)\]\((https?:\/\/[^\s)]+|\/[A-Za-z0-9/_-]*)\)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[1]) out.push(<strong key={m.index}>{m[1]}</strong>);
    else if (m[2]) out.push(<em key={m.index}>{m[2]}</em>);
    else if (m[4].startsWith('/')) out.push(appLink ? appLink(m[4], m[3], m.index) : <a key={m.index} href={m[4]}>{m[3]}</a>);
    else out.push(<a key={m.index} href={m[4]} target="_blank" rel="noreferrer">{m[3]}</a>);
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}
