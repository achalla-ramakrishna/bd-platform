import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { formatDate } from '../lib/format';
import { WHATS_NEW, type WhatsNewEntry } from '../whatsNew';

const SEEN_KEY = 'whatsNew.seen';

/** Ids this browser has already shown on the What's new page (kept per device). */
export function seenIds(): Set<string> {
  try {
    return new Set(JSON.parse(localStorage.getItem(SEEN_KEY) ?? '[]') as string[]);
  } catch {
    return new Set();
  }
}

function markAllSeen(entries: WhatsNewEntry[]) {
  try {
    localStorage.setItem(SEEN_KEY, JSON.stringify([...new Set([...seenIds(), ...entries.map((e) => e.id)])]));
  } catch {
    // Storage may be blocked; the badge then stays for this visit only.
  }
  window.dispatchEvent(new Event('whats-new-seen'));
}

/** How many entries this browser hasn't seen, kept up to date when the page marks them seen. */
export function useUnseenCount(entries: WhatsNewEntry[] = WHATS_NEW): number {
  const count = () => {
    const seen = seenIds();
    return entries.filter((e) => !seen.has(e.id)).length;
  };
  const [n, setN] = useState(count);
  useEffect(() => {
    const update = () => setN(count());
    window.addEventListener('whats-new-seen', update);
    window.addEventListener('storage', update);
    return () => {
      window.removeEventListener('whats-new-seen', update);
      window.removeEventListener('storage', update);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return n;
}

/** What's new: every change people will notice, newest first, with how to try it. */
export function WhatsNewPage({ entries = WHATS_NEW }: { entries?: WhatsNewEntry[] }) {
  // Remember what was new when the page opened, so it stays highlighted while you read.
  const [unseen] = useState(() => {
    const seen = seenIds();
    return new Set(entries.filter((e) => !seen.has(e.id)).map((e) => e.id));
  });
  useEffect(() => {
    markAllSeen(entries);
  }, [entries]);
  return (
    <>
      <h1>What's new</h1>
      <p className="lead">Everything that changed in the BD platform, newest first, with how to try it.</p>
      <ol className="whats-new">
        {entries.map((e) => (
          <li key={e.id} className={unseen.has(e.id) ? 'card new' : 'card'}>
            <div className="whats-new-head">
              <h2>{e.title}</h2>
              <span className="muted small">{formatDate(e.date)}</span>
              {unseen.has(e.id) && <span className="pill accent">New</span>}
            </div>
            <p>{e.summary}</p>
            {e.steps.length > 0 && (
              <>
                <div className="label">Try it</div>
                <ol className="steps">{e.steps.map((s) => <li key={s}>{s}</li>)}</ol>
              </>
            )}
            {e.link && <Link to={e.link.to}>{e.link.label} →</Link>}
          </li>
        ))}
      </ol>
    </>
  );
}
