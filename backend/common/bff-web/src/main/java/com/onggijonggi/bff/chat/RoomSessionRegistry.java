package com.onggijonggi.bff.chat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

	private static final int WARMUP_BUFFER_SIZE = 1;

	private final ConcurrentMap<UUID, RoomState> rooms = new ConcurrentHashMap<>();

	public RoomMembership join(UUID threadId, UUID connectionId) {
		RoomState room = rooms.compute(threadId, (ignored, current) -> {
			RoomState joined = current == null ? new RoomState() : current;
			joined.add(connectionId);
			return joined;
		});
		return new RoomMembership(room.generation(), room.frames());
	}

	/**
	 * 현재 방 세대에만 프레임을 방송한다.
	 *
	 * @return 방이 없거나 generation이 달라 stale이면 {@code false}; 현재 sink 고장은 예외로 전파한다.
	 */
	public boolean broadcastIfCurrent(UUID threadId, UUID roomGeneration, WsFrame frame) {
		RoomState room = rooms.get(threadId);
		if (room == null || !room.generation().equals(roomGeneration)) {
			return false;
		}
		return room.emitIfActive(frame);
	}

	/** @return 마지막 연결이 퇴장해 비워진 방 세대. */
	public Optional<UUID> leave(UUID threadId, UUID connectionId) {
		UUID[] emptiedGeneration = new UUID[1];
		rooms.computeIfPresent(threadId, (ignored, current) -> {
			if (!current.removeAndCompleteIfEmpty(connectionId)) {
				return current;
			}
			emptiedGeneration[0] = current.generation();
			return null;
		});
		return Optional.ofNullable(emptiedGeneration[0]);
	}

	public record RoomMembership(UUID generation, Flux<WsFrame> frames) {
	}

	private static final class RoomState {

		private final Set<UUID> connections = new HashSet<>();

		private final UUID generation = UUID.randomUUID();

		private boolean active = true;

		private final Sinks.Many<WsFrame> frames = Sinks.many()
				.multicast()
				.onBackpressureBuffer(WARMUP_BUFFER_SIZE, false);

		synchronized void add(UUID connectionId) {
			connections.add(connectionId);
		}

		UUID generation() {
			return generation;
		}

		synchronized boolean removeAndCompleteIfEmpty(UUID connectionId) {
			connections.remove(connectionId);
			if (!connections.isEmpty()) {
				return false;
			}
			active = false;
			frames.tryEmitComplete();
			return true;
		}

		Flux<WsFrame> frames() {
			return frames.asFlux();
		}

		synchronized boolean emitIfActive(WsFrame frame) {
			if (!active) {
				return false;
			}
			Sinks.EmitResult result = frames.tryEmitNext(frame);
			if (result.isFailure()) {
				throw new IllegalStateException("room frame emission failed: " + result);
			}
			return true;
		}
	}

}
