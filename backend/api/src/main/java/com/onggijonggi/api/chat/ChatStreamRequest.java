package com.onggijonggi.api.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
 * @param modelId 게이트웨이 model_list의 model_name(별칭). 화면에서 고른 값이 그대로 오고,
 *                유효성은 게이트웨이가 판정한다(LlmChatStreamService 참조)
 * @param messages 전체 대화 이력({role, content}만 포함하는 최소 스키마)
 */
public record ChatStreamRequest(
		@NotNull UUID sessionId,
		@NotBlank String modelId,
		@NotEmpty List<@Valid ChatMessage> messages
) {

	/** HTTP 1:1 저장은 마지막 입력만 HUMAN으로 기록하므로 마지막 role은 user여야 한다. */
	@AssertTrue(message = "마지막 메시지의 role은 user여야 합니다.")
	public boolean hasUserLastMessage() {
		return messages != null && !messages.isEmpty() && "user".equals(messages.get(messages.size() - 1).role());
	}
}
