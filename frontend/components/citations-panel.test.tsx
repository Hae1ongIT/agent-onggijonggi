// @vitest-environment jsdom

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { CitationsPanel } from './citations-panel';

describe('CitationsPanel', () => {
  it('hides the loading indicator after an empty citation result', () => {
    const { rerender } = render(
      <CitationsPanel state={{ status: 'loading' }} />,
    );
    expect(screen.getByText('근거 검색 중...')).toBeTruthy();

    rerender(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
        }}
      />,
    );

    expect(screen.queryByText('근거 검색 중...')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
