package com.onggijonggi.bff.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.bff.user.UserProvisioningService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Class Name : PersistingChatStreamServiceTest.java
 * Description : PersistingChatStreamService 데코레이터의 오케스트레이션(프로비저닝→세션/메시지 저장→
 *               LLM 위임→assistant 저장, 저장 실패 시 채팅은 그대로 진행)을 순수 단위 테스트로 검증한다.
 *               Spring 컨텍스트·실 DB 없이 전 의존성을 Mockito로 대체하고, JWT는
 *               ReactiveSecurityContextHolder.withAuthentication(...)로 리액터 Context에 직접 주입한다.
 */
@ExtendWith(MockitoExtension.class)
class PersistingChatStreamServiceTest {

	@Mock
	private LlmChatStreamService delegate;
	@Mock
	private UserProvisioningService userProvisioningService;
	@Mock
	private ChatSessRepository chatSessRepository;
	@Mock
	private ChatMsgRepository chatMsgRepository;

	private PersistingChatStreamService service;

	@BeforeEach
	void setUp() {
		service = new PersistingChatStreamService(delegate, userProvisioningService, chatSessRepository, chatMsgRepository);
	}

	private static JwtAuthenticationToken authToken(String subject) {
		Jwt jwt = Jwt.withTokenValue("test-token")
				.header("alg", "none")
				.claim("sub", subject)
				.build();
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
	}

	@Test
	void persistsUserAndAssistantMessagesAroundStream() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "안녕")));

		when(userProvisioningService.resolveOrProvision("sub-1")).thenReturn(Mono.just(userId));
		when(chatSessRepository.findById(sessionId)).thenReturn(Optional.empty());
		when(delegate.streamChat(request)).thenReturn(Flux.just("hi", " there"));

		StepVerifier.create(service.streamChat(request)
						.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authToken("sub-1"))))
				.expectNext("hi", " there")
				.verifyComplete();

		verify(chatSessRepository).save(argThat(sess -> sess.getId().equals(sessionId) && sess.getUserId().equals(userId)));
		verify(chatMsgRepository).save(argThat(msg -> "user".equals(msg.getRole()) && "안녕".equals(msg.getContent())));
		verify(chatMsgRepository, timeout(1000))
				.save(argThat(msg -> "assistant".equals(msg.getRole()) && "hi there".equals(msg.getContent())));
	}

	@Test
	void reusesExistingSessionWithoutCreatingNewRow() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "또 물어봄")));

		when(userProvisioningService.resolveOrProvision("sub-1")).thenReturn(Mono.just(userId));
		when(chatSessRepository.findById(sessionId)).thenReturn(Optional.of(new ChatSess(sessionId, userId, "또 물어봄")));
		when(delegate.streamChat(request)).thenReturn(Flux.just("ok"));

		StepVerifier.create(service.streamChat(request)
						.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authToken("sub-1"))))
				.expectNext("ok")
				.verifyComplete();

		verify(chatSessRepository, never()).save(any());
	}

	@Test
	void skipsPersistingWhenSessionOwnedByAnotherUser() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "남의 세션에 끼어들기")));

		when(userProvisioningService.resolveOrProvision("sub-1")).thenReturn(Mono.just(userId));
		when(chatSessRepository.findById(sessionId)).thenReturn(Optional.of(new ChatSess(sessionId, otherUserId, "원래 제목")));
		when(delegate.streamChat(request)).thenReturn(Flux.just("ok"));

		StepVerifier.create(service.streamChat(request)
						.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authToken("sub-1"))))
				.expectNext("ok")
				.verifyComplete();

		verify(chatSessRepository, never()).save(any());
		verify(chatMsgRepository, never()).save(any());
	}

	@Test
	void streamsNormallyEvenWhenProvisioningFails() {
		UUID sessionId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "안녕")));

		when(userProvisioningService.resolveOrProvision("sub-1")).thenReturn(Mono.error(new RuntimeException("db down")));
		when(delegate.streamChat(request)).thenReturn(Flux.just("hi"));

		StepVerifier.create(service.streamChat(request)
						.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authToken("sub-1"))))
				.expectNext("hi")
				.verifyComplete();

		verify(chatSessRepository, never()).save(any());
		verify(chatMsgRepository, never()).save(argThat(msg -> "user".equals(msg.getRole())));
		verify(chatMsgRepository, timeout(1000)).save(argThat(msg -> "assistant".equals(msg.getRole())));
	}

}
