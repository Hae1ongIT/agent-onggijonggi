/** 참여자 명단 GET의 오래된 결과를 무효화하는 작은 요청 세대 게이트(#173). */
export function createParticipantRefreshGate() {
  let current = 0;

  return {
    begin(): number {
      current += 1;
      return current;
    },
    isCurrent(requestId: number): boolean {
      return requestId === current;
    },
    invalidate(): void {
      current += 1;
    },
  };
}
