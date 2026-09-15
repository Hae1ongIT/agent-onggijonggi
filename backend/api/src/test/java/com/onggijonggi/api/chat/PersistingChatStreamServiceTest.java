package com.onggijonggi.api.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Class Name : PersistingChatStreamServiceTest.java
 * Description : HTTP 1:1 스트림이 DIRECT turn 저장·응답 AGENT 완료를 연결하고, 404는 LLM 호출 전에
 *               전파하며 내부 저장 오류는 기존 text stream을 막지 않는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PersistingChatStreamServiceTest {

	@Mock
	private LlmChatStreamService delegate;
	@Mock
	private CurrentActorProvider currentActorProvider;
	@Mock
	private DirectChatTurnService directChatTurnService;

	private PersistingChatStreamService service;

	@BeforeEach
	void setUp() {
		service = new PersistingChatStreamService(delegate, currentActorProvider, directChatTurnService);
	}

	@Test
	void persistsDirectTurnAndCompletesItsPendingAgentMessage() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID agentMessageId = UUID.randomUUID();
		ChatStreamRequest request = request(sessionId, " 안녕 ");
		when(currentActorProvider.currentActor()).thenReturn(Mono.just(new CurrentActor(userId, "sub-1", "sub-1")));
		when(directChatTurnService.prepareOrCreateBlocking(eq(sessionId), eq(userId), eq(" 안녕 "), eq("안녕")))
				.thenReturn(new DirectChatTurnService.StoredTurn(agentMessageId));
		when(delegate.streamChat(request)).thenReturn(Flux.just("hi", " there"));

		StepVerifier.create(service.streamChat(request))
				.expectNext("hi", " there")
				.verifyComplete();

		verify(directChatTurnService, timeout(1000)).completeAgentReplyBlocking(agentMessageId, "hi there");
	}

	@Test
	void doesNotCallLlmWhenDirectThreadIsNotOwnedByTheActor() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		ChatStreamRequest request = request(sessionId, "안녕");
		when(currentActorProvider.currentActor()).thenReturn(Mono.just(new CurrentActor(userId, "sub-1", "sub-1")));
		when(directChatTurnService.prepareOrCreateBlocking(any(), any(), any(), any()))
				.thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

		StepVerifier.create(service.streamChat(request))
				.expectErrorSatisfies(error -> {
					ResponseStatusException status = (ResponseStatusException) error;
					org.assertj.core.api.Assertions.assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
				})
				.verify();

		verify(delegate, never()).streamChat(any());
	}

	@Test
	void continuesStreamingWhenDirectStorageFailsInternally() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		ChatStreamRequest request = request(sessionId, "안녕");
		when(currentActorProvider.currentActor()).thenReturn(Mono.just(new CurrentActor(userId, "sub-1", "sub-1")));
		when(directChatTurnService.prepareOrCreateBlocking(any(), any(), any(), any()))
				.thenThrow(new IllegalStateException("database unavailable"));
		when(delegate.streamChat(request)).thenReturn(Flux.just("reply"));

		StepVerifier.create(service.streamChat(request))
				.expectNext("reply")
				.verifyComplete();

		verify(directChatTurnService, never()).completeAgentReplyBlocking(any(), any());
	}

	@Test
	void retriesTheExistingDirectThreadAfterCreateIdCollision() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID agentMessageId = UUID.randomUUID();
		ChatStreamRequest request = request(sessionId, "안녕");
		when(currentActorProvider.currentActor()).thenReturn(Mono.just(new CurrentActor(userId, "sub-1", "sub-1")));
		when(directChatTurnService.prepareOrCreateBlocking(any(), any(), any(), any()))
				.thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
		when(directChatTurnService.prepareExistingBlocking(sessionId, userId, "안녕"))
				.thenReturn(new DirectChatTurnService.StoredTurn(agentMessageId));
		when(delegate.streamChat(request)).thenReturn(Flux.just("reply"));

		StepVerifier.create(service.streamChat(request))
				.expectNext("reply")
				.verifyComplete();

		verify(directChatTurnService).prepareExistingBlocking(sessionId, userId, "안녕");
	}

	private ChatStreamRequest request(UUID sessionId, String content) {
		return new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", content)));
	}

}
