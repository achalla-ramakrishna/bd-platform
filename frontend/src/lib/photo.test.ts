import { describe, expect, it } from 'vitest';
import { centreSquare, clampSquare, initials, squareFromBox } from './photo';

describe('photo crop', () => {
  it('turns the box Claude gives into a padded square inside the image', () => {
    const s = squareFromBox({ image: 0, x: 0.1, y: 0.2, width: 0.1, height: 0.15 }, 1000, 800);
    // 0.15 * 800 = 120 px tall box, padded to 130; centred on (150, 220).
    expect(s).toEqual({ x: 85, y: 155, side: 130 });
  });

  it('keeps a square at the edge inside the image', () => {
    expect(squareFromBox({ image: 0, x: 0, y: 0, width: 0.05, height: 0.05 }, 1000, 1000)).toEqual({ x: 0, y: 0, side: 54 });
    expect(clampSquare({ x: 950, y: -20, side: 100 }, 1000, 500)).toEqual({ x: 900, y: 0, side: 100 });
  });

  it('uses the centre square for a chosen file', () => {
    expect(centreSquare(400, 300)).toEqual({ x: 50, y: 0, side: 300 });
  });

  it('makes initials for people without a photo', () => {
    expect(initials('Person Twenty')).toBe('PT');
    expect(initials('')).toBe('?');
  });
});
