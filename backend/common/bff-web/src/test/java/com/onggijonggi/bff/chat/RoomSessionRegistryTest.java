package com.onggijonggi.bff.chat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

class RoomSessionRegistryTest {

	private final RoomSessionRegistry registry = new RoomSessionRegistry();

	@Test
	void broadcastsToSenderAndPeersButNotOtherRooms() {
		UUID roomId = UUID.randomUUID();
		UUID otherRoomId = UUID.randomUUID();
		UUID sender = UUID.randomUUID();
		List<WsFrame> first = new CopyOnWriteArrayList<>();
		List<WsFrame> second = new CopyOnWriteArrayList<>();
		List<WsFrame> other = new CopyOnWriteArrayList<>();

		RoomSessionRegistry.RoomMembership firstMembership = registry.join(roomId, UUID.randomUUID());
		RoomSessionRegistry.RoomMembership secondMembership = registry.join(roomId, UUID.randomUUID());
		RoomSessionRegistry.RoomMembership otherMembership = registry.join(otherRoomId, UUID.randomUUID());
		Disposable firstSubscription = firstMembership.frames().subscribe(first::add);
		Disposable secondSubscription = secondMembership.frames().subscribe(second::add);
		Disposable otherSubscription = otherMembership.frames().subscribe(other::add);

		ChatMessageFrame expected = new ChatMessageFrame(roomId, sender, "hello");
		assertThat(registry.broadcastIfCurrent(roomId, firstMembership.generation(), expected)).isTrue();
		assertThat(first).containsExactly(expected);
		assertThat(second).containsExactly(expected);
		assertThat(other).isEmpty();

		firstSubscription.dispose();
		secondSubscription.dispose();
		otherSubscription.dispose();
	}

	@Test
	void concurrentBroadcastsHaveTheSameOrderForEverySubscriber() throws Exception {
		UUID roomId = UUID.randomUUID();
		List<WsFrame> first = new CopyOnWriteArrayList<>();
		List<WsFrame> second = new CopyOnWriteArrayList<>();
		RoomSessionRegistry.RoomMembership firstMembership = registry.join(roomId, UUID.randomUUID());
		RoomSessionRegistry.RoomMembership secondMembership = registry.join(roomId, UUID.randomUUID());
		Disposable firstSubscription = firstMembership.frames().subscribe(first::add);
		Disposable secondSubscription = secondMembership.frames().subscribe(second::add);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<?> left = executor.submit(() -> broadcastRange(roomId, firstMembership.generation(), "left", start));
			Future<?> right = executor.submit(() -> broadcastRange(roomId, firstMembership.generation(), "right", start));
			start.countDown();
			left.get(5, TimeUnit.SECONDS);
			right.get(5, TimeUnit.SECONDS);

			assertThat(first).hasSize(200);
			assertThat(second).containsExactlyElementsOf(first);
		} finally {
			executor.shutdownNow();
			firstSubscription.dispose();
			secondSubscription.dispose();
		}
	}

	@Test
	void overflowingConnectionBufferSignalsOnlyThatSubscriber() {
		UUID roomId = UUID.randomUUID();
		UUID slowConnectionId = UUID.randomUUID();
		UUID fastConnectionId = UUID.randomUUID();
		Sinks.One<Void> slowOverflow = Sinks.one();
		Sinks.One<Void> fastOverflow = Sinks.one();
		AtomicBoolean slowOverflowed = new AtomicBoolean();
		AtomicBoolean fastOverflowed = new AtomicBoolean();
		List<WsFrame> fastFrames = new CopyOnWriteArrayList<>();

		slowOverflow.asMono().doOnSuccess(ignored -> slowOverflowed.set(true)).subscribe();
		fastOverflow.asMono().doOnSuccess(ignored -> fastOverflowed.set(true)).subscribe();

		BaseSubscriber<WsFrame> slowSubscriber = new BaseSubscriber<>() {
			@Override
			protected void hookOnSubscribe(Subscription subscription) {
				// 연결별 버퍼를 채우기 위해 의도적으로 demand를 요청하지 않는다.
			}
		};
		RoomSessionRegistry.RoomMembership slowMembership = registry.join(roomId, slowConnectionId);
		RoomSessionRegistry.RoomMembership fastMembership = registry.join(roomId, fastConnectionId);
		CollabWebSocketHandler.bufferForConnection(
				slowMembership.frames(), slowOverflow).subscribe(slowSubscriber);
		Disposable fastSubscription = CollabWebSocketHandler.bufferForConnection(
				fastMembership.frames(), fastOverflow).subscribe(fastFrames::add);

		for (int i = 0; i < 257; i++) {
			registry.broadcastIfCurrent(roomId, slowMembership.generation(),
					new ChatMessageFrame(roomId, UUID.randomUUID(), "message-" + i));
		}

		assertThat(slowOverflowed).isTrue();
		assertThat(fastOverflowed).isFalse();
		assertThat(fastFrames).hasSize(257);

		slowSubscriber.cancel();
		fastSubscription.dispose();
		registry.leave(roomId, slowConnectionId);
		registry.leave(roomId, fastConnectionId);
	}

	@Test
	void aNewRoomStateSurvivesAfterThePreviousLastMemberLeaves() {
		UUID roomId = UUID.randomUUID();
		UUID oldConnectionId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership oldMembership = registry.join(roomId, oldConnectionId);
		Disposable oldSubscription = oldMembership.frames().subscribe();

		registry.leave(roomId, oldConnectionId);
		oldSubscription.dispose();

		UUID newConnectionId = UUID.randomUUID();
		List<WsFrame> received = new CopyOnWriteArrayList<>();
		RoomSessionRegistry.RoomMembership newMembership = registry.join(roomId, newConnectionId);
		Disposable newSubscription = newMembership.frames().subscribe(received::add);
		assertThat(registry.broadcastIfCurrent(roomId, newMembership.generation(),
				new ChatMessageFrame(roomId, UUID.randomUUID(), "new room"))).isTrue();

		assertThat(received).hasSize(1);

		newSubscription.dispose();
		registry.leave(roomId, newConnectionId);
	}

	@Test
	void staleGenerationCannotDeliverIntoARecreatedRoom() {
		UUID roomId = UUID.randomUUID();
		UUID oldConnectionId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership oldMembership = registry.join(roomId, oldConnectionId);
		Disposable oldSubscription = oldMembership.frames().subscribe();

		assertThat(registry.leave(roomId, oldConnectionId)).contains(oldMembership.generation());
		oldSubscription.dispose();

		UUID newConnectionId = UUID.randomUUID();
		List<WsFrame> received = new CopyOnWriteArrayList<>();
		RoomSessionRegistry.RoomMembership newMembership = registry.join(roomId, newConnectionId);
		Disposable newSubscription = newMembership.frames().subscribe(received::add);
		try {
			assertThat(newMembership.generation()).isNotEqualTo(oldMembership.generation());
			assertThat(registry.broadcastIfCurrent(roomId, oldMembership.generation(),
					new ChatMessageFrame(roomId, UUID.randomUUID(), "stale"))).isFalse();
			assertThat(received).isEmpty();
		} finally {
			newSubscription.dispose();
			registry.leave(roomId, newConnectionId);
		}
	}

	@Test
	void concurrentLastLeaveAndBroadcastNeverDeliverTheOldGenerationToANewRoom() throws Exception {
		UUID roomId = UUID.randomUUID();
		UUID oldConnectionId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership oldMembership = registry.join(roomId, oldConnectionId);
		Disposable oldSubscription = oldMembership.frames().subscribe();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<?> leave = executor.submit(() -> {
				await(start);
				registry.leave(roomId, oldConnectionId);
			});
			Future<Boolean> broadcast = executor.submit(() -> {
				await(start);
				return registry.broadcastIfCurrent(roomId, oldMembership.generation(),
						new ChatMessageFrame(roomId, UUID.randomUUID(), "racing"));
			});
			start.countDown();
			leave.get(5, TimeUnit.SECONDS);
			broadcast.get(5, TimeUnit.SECONDS);

			UUID newConnectionId = UUID.randomUUID();
			List<WsFrame> received = new CopyOnWriteArrayList<>();
			RoomSessionRegistry.RoomMembership newMembership = registry.join(roomId, newConnectionId);
			Disposable newSubscription = newMembership.frames().subscribe(received::add);
			try {
				assertThat(registry.broadcastIfCurrent(roomId, oldMembership.generation(),
						new ChatMessageFrame(roomId, UUID.randomUUID(), "stale"))).isFalse();
				assertThat(received).isEmpty();
			} finally {
				newSubscription.dispose();
				registry.leave(roomId, newConnectionId);
			}
		} finally {
			executor.shutdownNow();
			oldSubscription.dispose();
		}
	}

	private void broadcastRange(UUID roomId, UUID roomGeneration, String prefix, CountDownLatch start) {
		await(start);
		for (int i = 0; i < 100; i++) {
			registry.broadcastIfCurrent(roomId, roomGeneration,
					new ChatMessageFrame(roomId, UUID.randomUUID(), prefix + i));
		}
	}

	private static void await(CountDownLatch start) {
		try {
			start.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

}
