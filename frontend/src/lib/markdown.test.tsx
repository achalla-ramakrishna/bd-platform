import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { Markdown } from './markdown';

describe('Markdown', () => {
  it('renders headings, nested lists, bold and safe links', () => {
    const { container } = render(<Markdown text={[
      '# Strategy',
      '## Who',
      '- **GCCs** first',
      '  - young ones',
      '1. Connect',
      '2. Give value',
      '',
      'See [the site](https://example-gcc.test/a).',
    ].join('\n')} />);
    expect(container.querySelector('h2')?.textContent).toBe('Strategy');
    expect(container.querySelector('h3')?.textContent).toBe('Who');
    expect(container.querySelector('ul > li > strong')?.textContent).toBe('GCCs');
    expect(container.querySelector('ul > li > ul > li')?.textContent).toBe('young ones');
    expect(container.querySelectorAll('ol > li')).toHaveLength(2);
    expect(container.querySelector('a')?.getAttribute('href')).toBe('https://example-gcc.test/a');
  });

  it('shows markup as text, never as HTML', () => {
    const { container } = render(<Markdown text={'<img src=x onerror=alert(1)> [x](javascript:alert(1))'} />);
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('a')).toBeNull();
    expect(container.textContent).toContain('<img');
  });
});

import { render as renderApp, screen as appScreen, cleanup as cleanupApp } from '@testing-library/react';
import { afterEach as afterEachApp, describe as describeApp, expect as expectApp, it as itApp } from 'vitest';

describeApp('Markdown app links', () => {
  afterEachApp(cleanupApp);
  itApp('hands links to pages in the app to the app link renderer, and keeps web links as they are', () => {
    renderApp(<Markdown text={'See [Person One](/conversations/12) and [docs](https://example-gcc.test/x)'}
      appLink={(path, label, key) => <b key={key} data-path={path}>{label}</b>} />);
    expectApp(appScreen.getByText('Person One').getAttribute('data-path')).toBe('/conversations/12');
    expectApp(appScreen.getByText('docs').getAttribute('href')).toBe('https://example-gcc.test/x');
  });
});
