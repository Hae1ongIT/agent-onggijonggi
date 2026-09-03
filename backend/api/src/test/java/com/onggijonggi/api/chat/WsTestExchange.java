package com.onggijonggi.api.chat;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Class Name : WsTestExchange.java
 * Description : WebSocket 통합 테스트에서 수신 demand가 생긴 뒤에만 송신하도록 교환 순서를 보장한다.
 */
final class WsTestExchange {

	// 이슈 #102 진단용 임시 로그(2026-09-03) — 클라이언트 쪽 연결 종료 시점을 서버의
	// AbortedException 발생 시각과 대조하기 위함. 원인이 확정되면 정리한다.
	private static final Logger log = LoggerFactory.getLogger(WsTestExchange.class);

	// 이슈 #102 진단용 검증 실험(2026-09-03, 9차) — reactor-netty 소스 분석으로 좁힌 경합 창
	// (WebsocketServerOperations의 핸드셰이크 응답 플러시와 rebind()+channel.read() 리스너
	// 콜백 사이, 같은 이벤트 루프의 콜백 큐잉만큼의 틈)을 클라이언트가 첫 프레임을 쓰기 전에
	// 벗어나게 한다. 재현율이 실제로 떨어지는지 CI로 확인하는 게 목적이라 값 자체엔 의미가
	// 없다 — 원인이 확정되면 이 지연과 함께 지운다.
	private static final Duration DIAG_102_SEND_DELAY = Duration.ofMillis(20);

	private WsTestExchange() {
	}

	static Mono<Void> exchange(WebSocketSession session,
			Function<WebSocketSession, Publisher<WebSocketMessage>> outbound, long expectedFrames,
			Consumer<WebSocketMessage> onInbound) {
		return exchange(session, outbound, expectedFrames, onInbound, () -> {
		});
	}

	static Mono<Void> exchange(WebSocketSession session,
			Function<WebSocketSession, Publisher<WebSocketMessage>> outbound, long expectedFrames,
			Consumer<WebSocketMessage> onInbound, Runnable onReceiveRequested) {
		int sessionIdentity = System.identityHashCode(session);
		Sinks.One<Void> receiveRequested = Sinks.one();
		Mono<Void> receive = session.receive()
				.doOnSubscribe(subscription -> log.info(
						"[diag-102-client] receive subscribed sessionIdentity={}", sessionIdentity))
				.doOnRequest(ignored -> {
					log.info("[diag-102-client] receive demand requested sessionIdentity={}", sessionIdentity);
					receiveRequested.tryEmitEmpty();
					onReceiveRequested.run();
				})
				.take(expectedFrames)
				.doOnNext(onInbound)
				.doOnCancel(() -> log.info("[diag-102-client] receive cancelled sessionIdentity={}", sessionIdentity))
				.doOnError(error -> log.error("[diag-102-client] receive errored sessionIdentity={} errorClass={} message={}",
						sessionIdentity, error.getClass().getName(), error.getMessage(), error))
				.doOnComplete(() -> log.info("[diag-102-client] receive completed sessionIdentity={}", sessionIdentity))
				.then();
		Mono<Void> send = receiveRequested.asMono()
				.then(Mono.delay(DIAG_102_SEND_DELAY))
				.then(Mono.defer(() -> {
					log.info("[diag-102-client] send about to subscribe sessionIdentity={}", sessionIdentity);
					return session.send(outbound.apply(session));
				}))
				.doOnSuccess(ignored -> log.info("[diag-102-client] send completed sessionIdentity={}", sessionIdentity))
				.doOnError(error -> log.error("[diag-102-client] send errored sessionIdentity={} errorClass={} message={}",
						sessionIdentity, error.getClass().getName(), error.getMessage(), error));
		return Mono.when(receive, send)
				.doFinally(signal -> log.info("[diag-102-client] exchange finished sessionIdentity={} signal={}",
						sessionIdentity, signal));
	}

}
