import { useState } from 'react';

/**
 * A company's logo from its website domain (the site's icon, via Google's public icon service),
 * with the company's initials when no icon loads. Only the domain leaves the browser.
 */
export function CompanyLogo({ domain, name, size = 28 }: { domain?: string | null; name: string; size?: number }) {
  const [failed, setFailed] = useState(false);
  const initials = name.split(/\s+/).filter((w) => /^[A-Za-z0-9]/.test(w)).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('');
  const style = { width: size, height: size, fontSize: Math.round(size * 0.4) };
  if (!domain || failed) {
    return <span className="logo logo-initials" style={style} aria-hidden="true">{initials || '?'}</span>;
  }
  return (
    <img
      className="logo"
      style={style}
      src={`https://www.google.com/s2/favicons?domain=${encodeURIComponent(domain)}&sz=${size * 2}`}
      alt=""
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}
