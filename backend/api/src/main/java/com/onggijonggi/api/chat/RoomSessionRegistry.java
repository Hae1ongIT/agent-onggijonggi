package com.onggijonggi.api.chat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Class Name : RoomSessionRegistry.java
 * Description : 방 단위 연결과 프레임 방송을 in-memory로 관리한다. WebSocketSession은
 *               {@link CollabWebSocketHandler}가 소유하며, in-memory sink 기반이라 단일
 *               서버 프로세스만 지원한다. 다중 인스턴스는 pub/sub와 분산 순서 보장이 필요하다.
 */
@Component
public class RoomSessionRegistry {

	// 이슈 #102 진단용 임시 로그(2026-09-03) — 원인이 확정되면 이 클래스의 로그는 정리한다.
	private static final Logger log = LoggerFactory.getLogger(RoomSessionRegistry.class);

	private static final int WARMUP_BUFFER_SIZE = 1;

	private final ConcurrentMap<UUID, RoomState> rooms = new ConcurrentHashMap<>();

	public Flux<WsFrame> join(UUID threadId, UUID connectionId) {
		RoomState room = rooms.compute(threadId, (ignored, current) -> {
			RoomState joined = current == null ? new RoomState() : current;
			joined.add(connectionId);
			return joined;
		});
		log.info("[diag-102] join threadId={} connectionId={} roomIdentity={}", threadId, connectionId,
				System.identityHashCode(room));
		return room.frames();
	}

	public void broadcast(UUID threadId, WsFrame frame) {
		RoomState room = rooms.get(threadId);
		log.info("[diag-102] broadcast threadId={} roomFound={} roomIdentity={}", threadId, room != null,
				room == null ? null : System.identityHashCode(room));
		if (room == null) {
			throw new IllegalStateException("room is not registered: " + threadId);
		}
		room.emit(frame, threadId);
	}

	public void leave(UUID threadId, UUID connectionId) {
		rooms.computeIfPresent(threadId, (ignored, current) -> {
			if (!current.removeAndCompleteIfEmpty(connectionId)) {
				return current;
			}
			log.info("[diag-102] leave completes room threadId={} connectionId={}", threadId, connectionId);
			return null;
		});
	}

	private static final class RoomState {

		private final Set<UUID> connections = new HashSet<>();

		private final Sinks.Many<WsFrame> frames = Sinks.many()
				.multicast()
				.onBackpressureBuffer(WARMUP_BUFFER_SIZE, false);

		synchronized void add(UUID connectionId) {
			connections.add(connectionId);
		}

		synchronized boolean removeAndCompleteIfEmpty(UUID connectionId) {
			connections.remove(connectionId);
			if (!connections.isEmpty()) {
				return false;
			}
			frames.tryEmitComplete();
			return true;
		}

		Flux<WsFrame> frames() {
			return frames.asFlux()
					.doOnSubscribe(subscription -> log.info("[diag-102] roomFrames subscribed identity={}",
							System.identityHashCode(this)))
					.doOnNext(frame -> log.info("[diag-102] roomFrames emitted downstream identity={} frame={}",
							System.identityHashCode(this), frame));
		}

		synchronized void emit(WsFrame frame, UUID threadId) {
			Sinks.EmitResult result = frames.tryEmitNext(frame);
			log.info("[diag-102] tryEmitNext threadId={} result={} identity={}", threadId, result,
					System.identityHashCode(this));
			if (result.isFailure()) {
				throw new IllegalStateException("room frame emission failed: " + result);
			}
		}
	}

}
