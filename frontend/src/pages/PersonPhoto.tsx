import { useEffect, useRef, useState, type PointerEvent } from 'react';
import type { PhotoBox } from '../lib/api';
import { clampSquare, cropToBlob, initials, loadImage, photoUrl, squareFromBox, type Square } from '../lib/photo';

/** A connection's photo, or their initials when there is none (ADR-0014). */
export function PersonPhoto({ c, size = 28 }: { c: { id: number; fullName: string; photoVersion?: number | null }; size?: number }) {
  const [failed, setFailed] = useState(false);
  const url = photoUrl(c);
  const style = { width: size, height: size, fontSize: Math.round(size * 0.38) };
  if (!url || failed) {
    return <span className="person-photo initials" style={style} aria-hidden="true">{initials(c.fullName)}</span>;
  }
  return <img className="person-photo" style={style} src={url} alt="" loading="lazy" onError={() => setFailed(true)} />;
}

type Shot = { url: string };

/**
 * Crops the person's photo from a screenshot the team member pasted. Starts from where Claude says the photo
 * is; "Adjust" shows the screenshot so they can drag a square themselves. Reports the cropped JPEG, or null
 * when they choose not to save a photo.
 */
export function PhotoChooser({ shots, box, defaultUse, label, onChange }: {
  shots: Shot[]; box?: PhotoBox | null; defaultUse: boolean; label: string; onChange: (photo: Blob | null) => void;
}) {
  const found = box && shots[box.image] ? box : null;
  const [image, setImage] = useState(found ? found.image : 0);
  const [img, setImg] = useState<HTMLImageElement | null>(null);
  const [square, setSquare] = useState<Square | null>(null);
  const [use, setUse] = useState(found !== null && defaultUse);
  const [adjusting, setAdjusting] = useState(false);
  const [preview, setPreview] = useState<string | null>(null);
  const [blob, setBlob] = useState<Blob | null>(null);
  const first = useRef(true);

  // Load the chosen screenshot; on first load, start from Claude's box.
  useEffect(() => {
    let live = true;
    if (!shots[image]) return;
    loadImage(shots[image].url).then((loaded) => {
      if (!live) return;
      setImg(loaded);
      if (first.current && found) setSquare(squareFromBox(found, loaded.naturalWidth, loaded.naturalHeight));
      first.current = false;
    }).catch(() => undefined);
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [image]);

  // Crop whenever the square changes.
  useEffect(() => {
    let live = true;
    if (!img || !square) {
      setBlob(null);
      setPreview(null);
      return;
    }
    cropToBlob(img, square).then((b) => {
      if (!live) return;
      setBlob(b);
      setPreview((old) => {
        if (old) URL.revokeObjectURL(old);
        return URL.createObjectURL(b);
      });
    }).catch(() => undefined);
    return () => { live = false; };
  }, [img, square]);

  useEffect(() => { onChange(use && blob ? blob : null); }, [use, blob, onChange]);

  if (shots.length === 0) return null;

  return (
    <div className="photo-chooser">
      <div className="photo-row">
        {preview
          ? <img className="person-photo" src={preview} alt="Cropped photo" style={{ width: 56, height: 56 }} />
          : <span className="person-photo initials" style={{ width: 56, height: 56 }} aria-hidden="true">?</span>}
        <div>
          {preview ? (
            <label className="check">
              <input type="checkbox" checked={use} onChange={(e) => setUse(e.target.checked)} /> {label}
            </label>
          ) : (
            <span className="muted small">{found ? 'Finding the photo…' : 'No photo of them was found in the screenshot.'}</span>
          )}
          <div>
            <button type="button" className="link-plain small" onClick={() => setAdjusting(!adjusting)}>
              {adjusting ? 'Done adjusting' : preview ? 'Adjust the crop' : 'Pick their photo from the screenshot'}
            </button>
          </div>
        </div>
      </div>
      {adjusting && img && (
        <>
          {shots.length > 1 && (
            <div className="actions small">
              Screenshot:{' '}
              {shots.map((_, i) => (
                <button key={i} type="button" className={i === image ? 'small-button' : 'small-button secondary'}
                  onClick={() => { setImage(i); setSquare(null); }}>{i + 1}</button>
              ))}
            </div>
          )}
          <p className="muted small">Drag a square over their face.</p>
          <CropArea img={img} square={square} onSquare={(s) => { setSquare(s); setUse(true); }} />
        </>
      )}
    </div>
  );
}

/** The screenshot with the chosen square drawn on it; dragging draws a new square. */
function CropArea({ img, square, onSquare }: { img: HTMLImageElement; square: Square | null; onSquare: (s: Square) => void }) {
  const box = useRef<HTMLDivElement>(null);
  const start = useRef<{ x: number; y: number } | null>(null);
  const [draft, setDraft] = useState<Square | null>(null);
  const W = img.naturalWidth;
  const H = img.naturalHeight;

  const point = (e: PointerEvent) => {
    const r = box.current!.getBoundingClientRect();
    return { x: ((e.clientX - r.left) / r.width) * W, y: ((e.clientY - r.top) / r.height) * H };
  };
  const squareTo = (p: { x: number; y: number }) => {
    const s = start.current!;
    const side = Math.max(Math.abs(p.x - s.x), Math.abs(p.y - s.y));
    return clampSquare({ x: p.x < s.x ? s.x - side : s.x, y: p.y < s.y ? s.y - side : s.y, side }, W, H);
  };
  const shown = draft ?? square;

  return (
    <div ref={box} className="crop-area" style={{ aspectRatio: `${W} / ${H}` }}
      onPointerDown={(e) => { e.preventDefault(); box.current!.setPointerCapture(e.pointerId); start.current = point(e); setDraft(null); }}
      onPointerMove={(e) => { if (start.current) setDraft(squareTo(point(e))); }}
      onPointerUp={(e) => {
        if (start.current) {
          const s = squareTo(point(e));
          if (s.side >= 16) onSquare(s);
        }
        start.current = null;
        setDraft(null);
      }}>
      <img src={img.src} alt="Screenshot to crop from" draggable={false} />
      {shown && (
        <span className="crop-square" style={{
          left: `${(shown.x / W) * 100}%`, top: `${(shown.y / H) * 100}%`,
          width: `${(shown.side / W) * 100}%`, height: `${(shown.side / H) * 100}%`,
        }} />
      )}
    </div>
  );
}
