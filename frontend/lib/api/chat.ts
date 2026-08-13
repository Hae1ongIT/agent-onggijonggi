/********************************************************
 파일명 : chat.ts (lib/api)
 설 명 : 채팅/인용 BFF 호출. 엔드포인트 URL·요청 바디 변환·인용 조회를 한 곳에 캡슐화한다.
 *********************************************************/

import {
  CHAT_CITATIONS_PATH,
  CHAT_STREAM_PATH,
  bffUrl,
  chatSessionPath,
} from './config';
import { authFetch } from './http';

/** 채팅 스트리밍 엔드포인트 URL (useChat `api` 에 사용). */
export const CHAT_STREAM_URL = bffUrl(CHAT_STREAM_PATH);

/** 와이어 메시지 모양. */
export interface WireMessage {
  role: string;
  content: string;
}

/** AI SDK UI 메시지를 와이어 모양 {role, content}으로 트리밍한다(id·parts 등 잉여 필드 제거). */
export function toWireMessages(
  messages: ReadonlyArray<{ role: string; content: string }>,
): WireMessage[] {
  return messages.map(({ role, content }) => ({ role, content }));
}

/** 채팅 요청 바디. */
export function buildChatRequestBody(params: {
  sessionId: string;
  modelId: string;
  messages: ReadonlyArray<{ role: string; content: string }>;
}) {
  return {
    sessionId: params.sessionId,
    modelId: params.modelId,
    messages: toWireMessages(params.messages),
  };
}

/** 인용 응답. */
export interface Citation {
  docId: string;
  title: string;
  snippet: string;
  score: number;
}

export interface CitationsResponse {
  citations: Citation[];
  restrictedResultsOmitted: boolean;
}

/** 근거 인용 조회. 검색 전용 엔드포인트(LLM 미호출)이며, chat.tsx가 채팅 전송과 병렬로 호출한다. */
export async function fetchCitations(params: {
  sessionId: string;
  query: string;
  modelId: string;
}): Promise<CitationsResponse> {
  const res = await authFetch(bffUrl(CHAT_CITATIONS_PATH), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
  if (!res.ok) {
    // resolveChatError가 그대로 파싱할 수 있도록 에러 봉투 원문을 담아 던진다.
    throw new Error(await res.text());
  }
  return res.json() as Promise<CitationsResponse>;
}

/** 서버에 저장된 세션을 삭제한다. 스토어의 deleteSession(zustand 로컬 액션)과 이름이 겹치지
 * 않도록 접미사를 둔다. 실패하면 예외를 던져 호출부가 toast로 안내하게 한다. */
export async function deleteSessionOnServer(sessionId: string): Promise<void> {
  const res = await authFetch(bffUrl(chatSessionPath(sessionId)), {
    method: 'DELETE',
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
}

/** 서버에 저장된 세션 제목을 바꾼다. 스토어의 renameSession(zustand 로컬 액션)과 이름이
 * 겹치지 않도록 접미사를 둔다. 실패하면 예외를 던져 호출부가 toast로 안내하게 한다. */
export async function renameSessionOnServer(
  sessionId: string,
  title: string,
): Promise<void> {
  const res = await authFetch(bffUrl(chatSessionPath(sessionId)), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
}
