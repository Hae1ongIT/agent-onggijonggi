/********************************************************
 파일명 : route.ts (app/(chat)/api/collab/threads/[threadId]/participants)
 설 명 : [MOCK] 참여자 목록 조회 목업(이슈 #23). 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 목업엔 참여자 테이블 자체가 없다(mocks/rooms.ts의 roomAccess() 참고 — WS 인가는 예약
 threadId 두 개만 막고 나머지는 전부 허용, role·참여자 개념이 없다). GET /api/collab/threads
 목업도 요청자가 누군지 보지 않고 고정 배열만 돌려준다 — "나"를 구별하는 mock 개념이 아예 없다.
 그래서 이 목업도 self를 실제 로그인 subject와 대조하지 않고 고정 배열의 한 행에 그냥 박아
 둔다(설계 문서 2.5) — 상태 변경이 없는 읽기 전용 화면이라 실제 사용자와 맞출 이유가 없다.

 초대·제거·위임(POST/DELETE/PUT)은 이 파일에 만들지 않는다 — 핸들러가 없으면 Next.js가
 자동으로 405를 준다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';
import type { ThreadParticipant } from '@/lib/api/collab';

/** 목업 방 다섯 개(mocks/rooms.ts) 전부 같은 고정 참여자 배열을 쓴다 — Sheet UI 확인용이라
 * 방마다 다르게 꾸밀 이유가 없다. */
const PARTICIPANTS: ThreadParticipant[] = [
  { subject: 'mock-owner', role: 'OWNER', self: true, displayName: '나' },
  {
    subject: 'mock-member',
    role: 'MEMBER',
    self: false,
    displayName: '동료 목업 사용자',
  },
];

export async function GET() {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  return Response.json(PARTICIPANTS);
}
