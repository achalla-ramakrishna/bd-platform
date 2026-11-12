import { useDictation } from '../lib/dictation';

/** A microphone button that types what you say into the box; hidden where the browser cannot listen. */
export function VoiceButton({ onText, disabled }: { onText: (text: string) => void; disabled?: boolean }) {
  const voice = useDictation(onText);
  if (!voice.supported) return null;
  return (
    <>
      <button type="button" className={voice.listening ? 'voice listening' : 'secondary voice'} disabled={disabled}
        aria-pressed={voice.listening} title="Speak instead of typing (your browser turns speech into text)"
        onClick={() => (voice.listening ? voice.stop() : voice.start())}>
        <span aria-hidden="true">{voice.listening ? '■' : '🎤'}</span> {voice.listening ? 'Stop' : 'Speak'}
      </button>
      {voice.listening && (
        <span className="voice-live small" aria-live="polite">
          <span className="dot" aria-hidden="true" /> Listening{voice.interim ? `: ${voice.interim}` : '… speak now'}
        </span>
      )}
      {voice.error && <span className="small" role="alert" style={{ color: 'var(--bad)' }}>{voice.error}</span>}
    </>
  );
}
