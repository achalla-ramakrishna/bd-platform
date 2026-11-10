import { describe, expect, it } from 'vitest';
import { level } from './HeatmapPage';

describe('heatmap level', () => {
  it('uses fixed thresholds so the grid darkens as activity grows', () => {
    expect([0, 1, 2, 3, 4, 6, 7, 30].map(level)).toEqual([0, 1, 2, 2, 3, 3, 4, 4]);
  });
});
