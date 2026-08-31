package com.onggijonggi.bff.chat;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollabWebSocketHandlerUnitTest {

	@Test
	void subscribesToReceiveAndSendExactlyOnce() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		AtomicInteger receiveSubscriptions = new AtomicInteger();
		RoomSessionRegistry registry = new RoomSessionRegistry();
		var provisioning = mock(com.onggijonggi.bff.user.UserProvisioningService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "ws-user";

		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/api/ws/" + threadId));
		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("ws-user")).thenReturn(Mono.just(userId));
		when(session.receive()).thenReturn(Flux.defer(() -> {
			receiveSubscriptions.incrementAndGet();
			return Flux.empty();
		}));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		CollabWebSocketHandler handler = new CollabWebSocketHandler(new JsonMapper(), registry, provisioning);

		handler.handle(session).block();

		assertThat(receiveSubscriptions).hasValue(1);
		verify(session, times(1)).receive();
		verify(session, times(1)).send(any());
	}

	@Test
	void sendsInternalErrorAndClosesNormallyWhenUserProvisioningFails() {
		UUID threadId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry();
		var provisioning = mock(com.onggijonggi.bff.user.UserProvisioningService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "failed-user";
		AtomicReference<String> sent = new AtomicReference<>();

		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/api/ws/" + threadId));
		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("failed-user")).thenReturn(Mono.error(new RuntimeException("db down")));
		when(session.textMessage(anyString())).thenAnswer(invocation -> new WebSocketMessage(
				WebSocketMessage.Type.TEXT, DefaultDataBufferFactory.sharedInstance.wrap(
						invocation.<String>getArgument(0).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.set(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		CollabWebSocketHandler handler = new CollabWebSocketHandler(new JsonMapper(), registry, provisioning);

		handler.handle(session).block();

		assertThat(sent.get()).contains("\"type\":\"error\"", "\"code\":\"INTERNAL_ERROR\"",
				"\"sessionId\":\"" + threadId + "\"");
		verify(session).close(CloseStatus.NORMAL);
	}

	@Test
	void closesWithCode4000WhenTheJwtExpires() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry();
		var provisioning = mock(com.onggijonggi.bff.user.UserProvisioningService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		JwtAuthenticationToken authentication = mock(JwtAuthenticationToken.class);
		Jwt jwt = mock(Jwt.class);

		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/api/ws/" + threadId));
		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(authentication));
		when(authentication.getToken()).thenReturn(jwt);
		when(jwt.getSubject()).thenReturn("expiring-user");
		when(jwt.getExpiresAt()).thenReturn(Instant.now().plusMillis(50));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("expiring-user")).thenReturn(Mono.just(userId));
		when(session.receive()).thenReturn(Flux.never());
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		CollabWebSocketHandler handler = new CollabWebSocketHandler(new JsonMapper(), registry, provisioning);

		handler.handle(session).block(java.time.Duration.ofSeconds(1));

		verify(session).close(argThat(status -> status.getCode() == 4000));
	}

}
