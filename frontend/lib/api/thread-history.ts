/********************************************************
 파일명 : thread-history.ts (lib/api)
 설 명 : DIRECT·COLLAB 공용 메시지 이력을 클라이언트에서 조회한다. #162는 SSR
        prefetch가 아닌 이 경로와 afterSeq catch-up을 정본으로 사용한다.
 *********************************************************/

import { bffUrl, threadMessagesPath } from './config';
import { authFetch } from './http';

export interface ThreadMessageItem {
  id: string;
  seq: number;
  athKind: 'HUMAN' | 'AGENT' | 'SYSTEM';
  status: 'PENDING' | 'COMPLETE' | 'DENIED' | 'FAILED' | 'CANCELLED';
  content: string;
  authorSubject: string | null;
  authorDisplayName: string | null;
  createdAt: string;
  completedAt: string | null;
}

export async function fetchThreadMessages(
  threadId: string,
  afterSeq?: number,
): Promise<{ status: number; messages: ThreadMessageItem[] }> {
  const response = await authFetch(
    bffUrl(threadMessagesPath(threadId, afterSeq)),
  );
  if (!response.ok) return { status: response.status, messages: [] };
  return {
    status: response.status,
    messages: (await response.json()) as ThreadMessageItem[],
  };
}
