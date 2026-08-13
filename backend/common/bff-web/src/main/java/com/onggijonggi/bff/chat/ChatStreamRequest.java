package com.onggijonggi.bff.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : ChatStreamRequest.java
 * Description : POST /api/chat/stream 요청 바디. 계약서
 *               스키마를 그대로 따른다.
 * @param sessionId 클라이언트가 생성한 세션 상관관계 ID
 * @param modelId 서버가 허용목록과 대조 검증하는 모델 식별자
 * @param messages 전체 대화 이력({role, content}만 포함하는 최소 스키마)
 */
public record ChatStreamRequest(
		@NotNull UUID sessionId,
		@NotBlank String modelId,
		@NotEmpty List<@Valid ChatMessage> messages
) {
}
