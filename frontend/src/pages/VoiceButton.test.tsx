import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { VoiceButton } from './VoiceButton';
import { appendSpoken } from '../lib/dictation';

class FakeRecognition {
  static last: FakeRecognition | null = null;
  lang = '';
  continuous = false;
  interimResults = false;
  onresult: ((e: unknown) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  onend: (() => void) | null = null;
  constructor() { FakeRecognition.last = this; }
  start() {}
  stop() { this.onend?.(); }
}

const result = (text: string, isFinal: boolean) => ({ isFinal, 0: { transcript: text }, length: 1 });

describe('voice input', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('is hidden where the browser cannot listen', () => {
    render(<VoiceButton onText={() => {}} />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows words as they are heard and hands over finished phrases', () => {
    vi.stubGlobal('webkitSpeechRecognition', FakeRecognition);
    const onText = vi.fn();
    render(<VoiceButton onText={onText} />);
    fireEvent.click(screen.getByRole('button', { name: /Speak/ }));
    const rec = FakeRecognition.last!;
    expect(rec.lang).toBe('en-IN');
    act(() => rec.onresult!({ resultIndex: 0, results: { length: 1, 0: result('called person', false) } }));
    expect(screen.getByText(/Listening: called person/)).toBeTruthy();
    act(() => rec.onresult!({ resultIndex: 0, results: { length: 1, 0: result('Called Person A at Example GCC', true) } }));
    expect(onText).toHaveBeenCalledWith('Called Person A at Example GCC');
    fireEvent.click(screen.getByRole('button', { name: /Stop/ }));
    expect(screen.getByRole('button', { name: /Speak/ })).toBeTruthy();
  });

  it('explains a blocked microphone', () => {
    vi.stubGlobal('webkitSpeechRecognition', FakeRecognition);
    render(<VoiceButton onText={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /Speak/ }));
    act(() => FakeRecognition.last!.onerror!({ error: 'not-allowed' }));
    expect(screen.getByRole('alert').textContent).toContain('microphone is blocked');
  });

  it('adds spoken words after what is typed', () => {
    expect(appendSpoken('', 'Hello')).toBe('Hello');
    expect(appendSpoken('Met them.', 'They want two engineers')).toBe('Met them. They want two engineers');
    expect(appendSpoken('Met them. ', 'Next')).toBe('Met them. Next');
  });
});
