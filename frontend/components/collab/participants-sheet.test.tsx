import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { createParticipantRefreshGate } from './participant-refresh';

const source = readFileSync(
  new URL('./participants-sheet.tsx', import.meta.url),
  'utf8',
);

describe('ParticipantsSheet(#173)', () => {
  it('겹친 명단 조회는 마지막 요청만 유효하게 만든다', () => {
    const gate = createParticipantRefreshGate();
    const first = gate.begin();
    const second = gate.begin();

    expect(gate.isCurrent(first)).toBe(false);
    expect(gate.isCurrent(second)).toBe(true);

    gate.invalidate();
    expect(gate.isCurrent(second)).toBe(false);
  });

  it('참여자 변경 액션 성공 뒤 직접 load를 호출하지 않는다', () => {
    const invite = source.slice(
      source.indexOf('async function handleInvite'),
      source.indexOf(
        '  /** 대기 초대를 거둔다.',
        source.indexOf('async function handleInvite'),
      ),
    );
    const revoke = source.slice(
      source.indexOf('async function handleRevoke'),
      source.indexOf(
        '  // 열 때의 최초 조회',
        source.indexOf('async function handleRevoke'),
      ),
    );
    const confirm = source.slice(
      source.indexOf('async function handleConfirm'),
      source.indexOf(
        '  return (',
        source.indexOf('async function handleConfirm'),
      ),
    );

    expect(invite).not.toContain('load()');
    expect(revoke).not.toContain('load()');
    expect(confirm).not.toContain('load()');
  });
});
