// Voice input with the browser's own speech recognition (Chrome, Edge, Safari). Nothing goes to our server
// as audio: the browser turns speech into text, and only the text is sent, like typing.

import { useCallback, useEffect, useRef, useState } from 'react';

interface Alternative { transcript: string }
interface Result { isFinal: boolean; 0: Alternative; length: number }
interface ResultEvent { resultIndex: number; results: { length: number; [i: number]: Result } }
interface ErrorEvent { error: string }
interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: ResultEvent) => void) | null;
  onerror: ((e: ErrorEvent) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
}
type RecognitionCtor = new () => Recognition;

function recognitionCtor(): RecognitionCtor | null {
  const w = globalThis as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

const ERRORS: Record<string, string> = {
  'not-allowed': 'The microphone is blocked. Allow it for this site in the browser, then try again.',
  'service-not-allowed': 'The microphone is blocked. Allow it for this site in the browser, then try again.',
  'audio-capture': 'No microphone was found.',
  network: 'Voice input needs an internet connection.',
};

/**
 * Dictation: {@code start} listens until {@code stop} (or a long pause); each finished phrase goes to
 * {@code onText}, and {@code interim} shows the words still being heard. Indian English by default.
 */
export function useDictation(onText: (text: string) => void, lang = 'en-IN') {
  const Ctor = recognitionCtor();
  const rec = useRef<Recognition | null>(null);
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [error, setError] = useState<string | null>(null);
  const handler = useRef(onText);
  handler.current = onText;

  const stop = useCallback(() => {
    rec.current?.stop();
  }, []);

  const start = useCallback(() => {
    if (!Ctor) return;
    setError(null);
    const r = new Ctor();
    r.lang = lang;
    r.continuous = true;
    r.interimResults = true;
    r.onresult = (e) => {
      let pending = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const result = e.results[i]!;
        const words = result[0].transcript;
        if (result.isFinal) {
          if (words.trim()) handler.current(words.trim());
        } else {
          pending += words;
        }
      }
      setInterim(pending.trim());
    };
    r.onerror = (e) => {
      if (e.error !== 'no-speech' && e.error !== 'aborted') setError(ERRORS[e.error] ?? 'Voice input stopped. Try again.');
    };
    r.onend = () => {
      setListening(false);
      setInterim('');
      rec.current = null;
    };
    rec.current = r;
    r.start();
    setListening(true);
  }, [Ctor, lang]);

  useEffect(() => () => rec.current?.stop(), []);

  return { supported: Ctor !== null, listening, interim, error, start, stop };
}

/** Adds dictated words to what is already typed, with a space between. */
export function appendSpoken(current: string, spoken: string): string {
  if (!current.trim()) return spoken;
  return /\s$/.test(current) ? current + spoken : `${current} ${spoken}`;
}
