package com.onggijonggi.bff.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Class Name : CollabMessageDispatcherTest.java
 * Description : 협업방 AI FIFO 실행, timeout, generation 취소 계약을 단위 테스트로 검증한다.
 */
class CollabMessageDispatcherTest {

	@Test
	void broadcastsOrdinaryMessagesWithoutCallingTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		assertThat(dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation())).isEmpty();

		assertThat(room.frames).containsExactly(new ChatMessageFrame(room.threadId, room.userId, "일반 발화"));
		verify(llm, times(0)).streamChat(any());
	}

	@Test
	void startsMentionedTurnsInFifoOrderAfterBroadcastingTheOriginalMessages() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux(), Flux.just("second"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI one"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI two"), room.membership.generation());
		verify(llm).streamChat(any());
		firstResponse.tryEmitValue("first");

		ArgumentCaptor<ChatStreamRequest> requests = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, times(2)).streamChat(requests.capture());
		assertThat(requests.getAllValues()).extracting(request -> request.messages().get(0).content())
				.containsExactly("one", "two");
		assertThat(room.frames).containsExactly(
				new ChatMessageFrame(room.threadId, room.userId, "@AI one"),
				new ChatMessageFrame(room.threadId, room.userId, "@AI two"),
				new ChatAnswerFrame(room.threadId, "first", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE),
				new ChatAnswerFrame(room.threadId, "second", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
	}

	@Test
	void rejectsBlankMentionPromptWithoutCallingTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		ErrorFrame error = dispatcher.dispatch(command(room, "@AI   "), room.membership.generation()).orElseThrow();

		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(room.frames).containsExactly(new ChatMessageFrame(room.threadId, room.userId, "@AI   "));
		verify(llm, times(0)).streamChat(any());
	}

	@Test
	void rejectsOnlyTheOverflowingPendingRequest() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, Duration.ofSeconds(120), 0,
				VirtualTimeScheduler.create());

		assertThat(dispatcher.dispatch(command(room, "@AI first"), room.membership.generation())).isEmpty();
		ErrorFrame error = dispatcher.dispatch(command(room, "@AI second"), room.membership.generation()).orElseThrow();

		assertThat(error.code()).isEqualTo("RATE_LIMITED");
		verify(llm).streamChat(any());
	}

	@Test
	void emptyOrWhitespaceOnlyOutputBroadcastsModelUnavailableWithoutDone() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("", "   "));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI hello"), room.membership.generation());

		assertThat(room.frames).hasSize(2);
		assertThat(room.frames.get(0)).isEqualTo(new ChatMessageFrame(room.threadId, room.userId, "@AI hello"));
		assertThat(room.frames.get(1)).isInstanceOf(ErrorFrame.class);
		assertThat(((ErrorFrame) room.frames.get(1)).code()).isEqualTo("MODEL_UNAVAILABLE");
	}

	@Test
	void preservesLeadingWhitespaceOnceTheOutputContainsMeaningfulText() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just(" ", "answer"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI hello"), room.membership.generation());

		assertThat(room.frames).containsExactly(
				new ChatMessageFrame(room.threadId, room.userId, "@AI hello"),
				new ChatAnswerFrame(room.threadId, " ", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "answer", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
	}

	@Test
	void timeoutCancelsTheUpstreamAndStartsTheNextQueuedTurn() {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		TestRoom room = new TestRoom();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)), Flux.just("next"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, Duration.ofSeconds(120), 20, scheduler);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second"), room.membership.generation());
		scheduler.advanceTimeBy(Duration.ofSeconds(120));

		assertThat(cancelled).isTrue();
		assertThat(room.frames).anySatisfy(frame -> {
			assertThat(frame).isInstanceOf(ErrorFrame.class);
			assertThat(((ErrorFrame) frame).code()).isEqualTo("MODEL_UNAVAILABLE");
		});
		assertThat(room.frames).contains(
				new ChatAnswerFrame(room.threadId, "next", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
		verify(llm, times(2)).streamChat(any());
	}

	@Test
	void failedTurnBroadcastsInternalErrorAndStartsTheNextQueuedTurn() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux(), Flux.just("next"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second"), room.membership.generation());
		firstResponse.tryEmitError(new IllegalStateException("gateway failure"));

		assertThat(room.frames).anySatisfy(frame -> {
			assertThat(frame).isInstanceOf(ErrorFrame.class);
			assertThat(((ErrorFrame) frame).code()).isEqualTo("INTERNAL_ERROR");
		});
		assertThat(room.frames).contains(
				new ChatAnswerFrame(room.threadId, "next", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
	}

	@Test
	void staleGenerationCancelsTheRunningTurnAndNeverEmitsAnErrorFrame() {
		TestRoom oldRoom = new TestRoom();
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
		CollabMessageDispatcher dispatcher = dispatcher(oldRoom.registry, llm);

		dispatcher.dispatch(command(oldRoom, "@AI first"), oldRoom.membership.generation());
		oldRoom.registry.leave(oldRoom.threadId, oldRoom.connectionId);
		oldRoom.subscription.dispose();
		UUID newConnectionId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership newMembership = oldRoom.registry.join(oldRoom.threadId, newConnectionId);
		List<WsFrame> newFrames = new CopyOnWriteArrayList<>();
		Disposable newSubscription = newMembership.frames().subscribe(newFrames::add);
		try {
			source.tryEmitNext("late result");

			assertThat(cancelled).isTrue();
			assertThat(newFrames).isEmpty();
		} finally {
			newSubscription.dispose();
			oldRoom.registry.leave(oldRoom.threadId, newConnectionId);
		}
	}

	@Test
	void cancelsTheRunningTurnWhenTheLastConnectionLeaves() {
		TestRoom room = new TestRoom();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		room.registry.leave(room.threadId, room.connectionId)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));

		assertThat(cancelled).isTrue();
	}

	@Test
	void cancelsAllRunningTurnsWhenTheServerShutsDown() {
		TestRoom room = new TestRoom();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.closeAllGenerations();

		assertThat(cancelled).isTrue();
	}

	@Test
	void disposesALateSubscriptionWhenTheGenerationClosedDuringSubscription() {
		TestRoom room = new TestRoom();
		AtomicReference<CollabMessageDispatcher> dispatcherRef = new AtomicReference<>();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.defer(() -> {
			dispatcherRef.get().closeGeneration(room.threadId, room.membership.generation());
			return Flux.<String>never().doOnCancel(() -> cancelled.set(true));
		}));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		dispatcherRef.set(dispatcher);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());

		assertThat(cancelled).isTrue();
	}

	@Test
	void rejectsInvalidAiSettingsAtStartup() {
		RoomSessionRegistry registry = new RoomSessionRegistry();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();

		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, " ", Duration.ofSeconds(1), 0, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, "model", Duration.ZERO, 0, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, "model", Duration.ofSeconds(1), -1, scheduler));
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm) {
		return dispatcher(registry, llm, Duration.ofSeconds(120), 20, VirtualTimeScheduler.create());
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			Duration timeout, int maxPending, VirtualTimeScheduler scheduler) {
		return new CollabMessageDispatcher(registry, llm, "test-model", timeout, maxPending, scheduler);
	}

	private static ChatMessageCommand command(TestRoom room, String content) {
		return new ChatMessageCommand(room.threadId, room.userId, content, "trace");
	}

	private static final class TestRoom {

		private final RoomSessionRegistry registry = new RoomSessionRegistry();

		private final UUID threadId = UUID.randomUUID();

		private final UUID connectionId = UUID.randomUUID();

		private final UUID userId = UUID.randomUUID();

		private final RoomSessionRegistry.RoomMembership membership = registry.join(threadId, connectionId);

		private final List<WsFrame> frames = new CopyOnWriteArrayList<>();

		private final Disposable subscription = membership.frames().subscribe(frames::add);
	}

}
