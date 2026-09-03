/********************************************************
 파일명 : route.ts (app/(chat)/api/collab/threads)
 설 명 : [MOCK] 협업 채널 목록 목업(이슈 #19). 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 라우트 그룹은 URL에 나타나지 않으므로 (chat) 안에 있어도 실제 경로는 /api/collab/threads
 그대로다 — 1:1 채팅의 목업 라우트와 같은 자리에 뒀다.

 정상·AI 최초 오류·AI 도중 오류·접근 거부 두 방식을 유효한 UUID 방으로 나눠 #47 UI를 수동 검증한다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';
import {
  ERROR_BEFORE_THREAD_ID,
  ERROR_MID_THREAD_ID,
  FORBIDDEN_FRAME_THREAD_ID,
  FORBIDDEN_HANDSHAKE_THREAD_ID,
  NORMAL_THREAD_ID,
} from '@/mocks/rooms';

export const runtime = 'nodejs';

/** 목업 방 다섯 개 — 정상 스트림, 오류 두 시점, 접근 거부 두 방식을 결정적으로 재현한다. */
const THREADS = [
  {
    id: NORMAL_THREAD_ID,
    title: '정상 스트리밍 확인방',
    participants: ['sujin', 'minho'],
  },
  {
    id: ERROR_BEFORE_THREAD_ID,
    title: 'AI 최초 오류 확인방',
    participants: [],
  },
  {
    id: ERROR_MID_THREAD_ID,
    title: 'AI 도중 오류 확인방',
    participants: [],
  },
  {
    id: FORBIDDEN_HANDSHAKE_THREAD_ID,
    title: '접근 거부(핸드셰이크) 확인방',
    participants: [],
  },
  {
    id: FORBIDDEN_FRAME_THREAD_ID,
    title: '접근 거부(프레임) 확인방',
    participants: [],
  },
];

export async function GET() {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  return Response.json(THREADS);
}
