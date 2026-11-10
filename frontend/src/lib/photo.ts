// Connection photos (ADR-0014): cropped in the browser from a screenshot the person took, then uploaded.

import type { PhotoBox } from './api';

/** A square in image pixels. */
export interface Square { x: number; y: number; side: number }

export const PHOTO_SIDE = 256;

export function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('The image could not be opened.'));
    img.src = src;
  });
}

/** The square around Claude's box (a little padding), kept inside the image. */
export function squareFromBox(box: PhotoBox, width: number, height: number): Square {
  const w = box.width * width;
  const h = box.height * height;
  const side = Math.min(Math.round(Math.max(w, h) * 1.08), width, height);
  const cx = (box.x + box.width / 2) * width;
  const cy = (box.y + box.height / 2) * height;
  return clampSquare({ x: Math.round(cx - side / 2), y: Math.round(cy - side / 2), side }, width, height);
}

/** The largest centred square, for photos chosen from a file. */
export function centreSquare(width: number, height: number): Square {
  const side = Math.min(width, height);
  return { x: Math.round((width - side) / 2), y: Math.round((height - side) / 2), side };
}

export function clampSquare(s: Square, width: number, height: number): Square {
  const side = Math.max(1, Math.min(Math.round(s.side), width, height));
  return {
    x: Math.max(0, Math.min(Math.round(s.x), width - side)),
    y: Math.max(0, Math.min(Math.round(s.y), height - side)),
    side,
  };
}

/** Draws the square at most PHOTO_SIDE pixels wide and returns it as a JPEG. */
export function cropToBlob(img: HTMLImageElement, s: Square): Promise<Blob> {
  const out = Math.min(PHOTO_SIDE, s.side);
  const canvas = document.createElement('canvas');
  canvas.width = out;
  canvas.height = out;
  const g = canvas.getContext('2d')!;
  g.fillStyle = '#fff';
  g.fillRect(0, 0, out, out);
  g.drawImage(img, s.x, s.y, s.side, s.side, 0, 0, out, out);
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('The photo could not be cropped.'))), 'image/jpeg', 0.9));
}

export function photoForm(blob: Blob): FormData {
  const form = new FormData();
  form.append('file', blob, 'photo.jpg');
  return form;
}

export function photoUrl(connection: { id: number; photoVersion?: number | null }): string | null {
  return connection.photoVersion ? `/api/v1/connections/${connection.id}/photo?v=${connection.photoVersion}` : null;
}

export function initials(name: string): string {
  return name.split(/\s+/).filter((w) => /^\p{L}/u.test(w)).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('') || '?';
}
