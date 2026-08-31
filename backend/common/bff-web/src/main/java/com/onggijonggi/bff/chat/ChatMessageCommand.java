package com.onggijonggi.bff.chat;

import java.util.UUID;

/** 검증을 마친 클라이언트 발화. traceId는 핸드셰이크가 아니라 이 메시지 처리 시도에 속한다. */
record ChatMessageCommand(UUID threadId, UUID from, String content, String traceId) {
}
