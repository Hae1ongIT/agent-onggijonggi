/********************************************************
 파일명 : route.ts (app/(chat)/api/chat/stream)
 설 명 : [MOCK] 채팅 스트리밍 목업. 프레이밍 없는 raw 텍스트 청크 스트림을 반환한다(streamProtocol:'text' 소비용).
 ⚠️ SSE의 `data:`/`event:` 프레이밍을 넣지 않는다. 실 BFF 설정 시 우회된다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';

export const runtime = 'nodejs';
export const maxDuration = 60;

interface ChatMessage {
  role: string;
  content: string;
}

interface ChatStreamRequest {
  sessionId?: string;
  modelId?: string;
  messages?: ChatMessage[];
}

/** 마지막 user 메시지를 골라 고정 문구 응답을 단어 단위로 40ms 간격 스트리밍해 토큰 스트림을 흉내낸다. */
export async function POST(request: Request) {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  const body = (await request.json()) as ChatStreamRequest;
  const lastUser = [...(body.messages ?? [])]
    .reverse()
    .find((m) => m.role === 'user');
  const prompt = lastUser?.content?.trim() ?? '';

  const reply = `「목업 응답」 요청을 받았습니다: "${prompt}". 이것은 프레이밍 없는 raw 텍스트 스트림입니다. 실 BFF 연동 전까지 CLIENT UI 를 독립 개발하기 위한 목업이며, streamProtocol:'text' 로 소비됩니다.`;

  const encoder = new TextEncoder();
  // 공백을 보존하며 단어 단위로 분리.
  const chunks = reply.split(/(\s+)/).filter((c) => c.length > 0);

  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
        await new Promise((r) => setTimeout(r, 40));
      }
      controller.close();
    },
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
    },
  });
}
