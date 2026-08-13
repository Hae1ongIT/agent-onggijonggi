/********************************************************
 파일명 : route.ts (app/(chat)/api/chat/citations)
 설 명 : [MOCK] 근거 인용 목업. LLM 미호출, 근거 문서 목록을 JSON으로 반환한다.
 소프트 필터링은 restrictedResultsOmitted 불리언으로만 신호한다(건수·제목 노출 금지).
 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';

export const runtime = 'nodejs';

interface CitationsRequest {
  sessionId?: string;
  query?: string;
  modelId?: string;
}

/** 질문에 "기밀/임원/대외비"가 포함되면 RBAC 소프트 필터링을 흉내내 restrictedResultsOmitted=true만 신호한다. */
export async function POST(request: Request) {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  const body = (await request.json()) as CitationsRequest;
  const query = body.query?.trim() ?? '';

  const citations = [
    {
      docId: 'doc-001',
      title: '사내 규정집 3장 — 계약 관리',
      snippet: `"${query}" 관련 조항: 계약 체결은 담당 부서장의 승인을 거쳐...`,
      score: 0.91,
    },
    {
      docId: 'doc-014',
      title: '표준 계약서 템플릿 v2',
      snippet: '위약금 조항은 제12조에 따라 계약금의 10% 를 상한으로...',
      score: 0.82,
    },
  ];

  const restrictedResultsOmitted = /기밀|임원|대외비/.test(query);

  return Response.json({ citations, restrictedResultsOmitted });
}
