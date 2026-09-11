import { describe, expect, it, vi } from 'vitest';
import { REFRESH_MARGIN_MS, needsProactiveRefresh } from './refresh-gate';

describe('needsProactiveRefresh', () => {
  const NOW = 1_700_000_000_000;

  it('만료 시각을 모르면(account.expires_at 누락) 리프레시한다', () => {
    expect(needsProactiveRefresh(undefined, NOW)).toBe(true);
    expect(needsProactiveRefresh(null, NOW)).toBe(true);
    expect(needsProactiveRefresh(0, NOW)).toBe(true);
  });

  it('만료까지 마진보다 넉넉히 남았으면 리프레시하지 않는다', () => {
    // 5분 뒤 만료 — 마진 30초와 한참 떨어져 있다.
    expect(needsProactiveRefresh(NOW + 5 * 60_000, NOW)).toBe(false);
  });

  it('만료까지 정확히 마진만큼 남은 경계에서는 리프레시한다', () => {
    expect(needsProactiveRefresh(NOW + REFRESH_MARGIN_MS, NOW)).toBe(true);
  });

  it('마진 경계를 1ms라도 넘겨 남아 있으면 리프레시하지 않는다', () => {
    expect(needsProactiveRefresh(NOW + REFRESH_MARGIN_MS + 1, NOW)).toBe(false);
  });

  it('마진 안쪽으로 들어온(수 초 남은) 토큰은 리프레시한다', () => {
    expect(needsProactiveRefresh(NOW + 10_000, NOW)).toBe(true);
  });

  it('이미 만료된 토큰은 리프레시한다', () => {
    expect(needsProactiveRefresh(NOW - 5_000, NOW)).toBe(true);
  });

  it('now를 생략하면 현재 시각으로 판정한다', () => {
    vi.spyOn(Date, 'now').mockReturnValue(NOW);
    try {
      expect(needsProactiveRefresh(NOW + 5 * 60_000)).toBe(false);
      expect(needsProactiveRefresh(NOW + 5_000)).toBe(true);
    } finally {
      vi.restoreAllMocks();
    }
  });
});
