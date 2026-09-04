package com.onggijonggi.api.chat;

import com.openai.errors.OpenAIServiceException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : CollabMessageDispatcher.java
 * Description : 협업방 원문 방송과 {@code @AI} FIFO 실행을 같은 room generation 단위로 직렬화한다.
 */
@Component
public class CollabMessageDispatcher {

	private final RoomSessionRegistry roomSessionRegistry;

	private final LlmChatStreamService llmChatStreamService;

	private final String modelId;

	private final Duration turnTimeout;

	private final int maxPendingPerRoom;

	private final Scheduler deadlineScheduler;

	private final ConcurrentMap<RoomKey, RoomAiState> states = new ConcurrentHashMap<>();

	@Autowired
	public CollabMessageDispatcher(RoomSessionRegistry roomSessionRegistry,
			LlmChatStreamService llmChatStreamService,
			@Value("${app.collab.ai.model:${spring.ai.openai.chat.options.model}}") String modelId,
			@Value("${app.collab.ai.turn-timeout:120s}") Duration turnTimeout,
			@Value("${app.collab.ai.max-pending-per-room:20}") int maxPendingPerRoom) {
		this(roomSessionRegistry, llmChatStreamService, modelId, turnTimeout, maxPendingPerRoom,
				Schedulers.parallel());
	}

	CollabMessageDispatcher(RoomSessionRegistry roomSessionRegistry, LlmChatStreamService llmChatStreamService,
			String modelId, Duration turnTimeout, int maxPendingPerRoom, Scheduler deadlineScheduler) {
		if (modelId == null || modelId.isBlank()) {
			throw new IllegalArgumentException("app.collab.ai.model must not be blank");
		}
		if (turnTimeout == null || turnTimeout.isZero() || turnTimeout.isNegative()) {
			throw new IllegalArgumentException("app.collab.ai.turn-timeout must be positive");
		}
		if (maxPendingPerRoom < 0) {
			throw new IllegalArgumentException("app.collab.ai.max-pending-per-room must not be negative");
		}
		this.roomSessionRegistry = roomSessionRegistry;
		this.llmChatStreamService = llmChatStreamService;
		this.modelId = modelId;
		this.turnTimeout = turnTimeout;
		this.maxPendingPerRoom = maxPendingPerRoom;
		this.deadlineScheduler = deadlineScheduler;
	}

	/** 원문을 먼저 방송하고, {@code @AI} 발화만 현재 방 세대의 FIFO에 등록한다. */
	public Optional<ErrorFrame> dispatch(ChatMessageCommand command, UUID roomGeneration) {
		RoomKey key = new RoomKey(command.threadId(), roomGeneration);

		while (true) {
			RoomAiState state = states.computeIfAbsent(key, ignored -> new RoomAiState());
			ActiveTurn turnToStart = null;
			onStateFetchedForTesting();

			synchronized (state) {
				if (state.closed) {
					// computeIfAbsent와 락 사이에 다른 스레드의 sink 고장 처리가 이 상태를 막 닫고
					// map에서 지웠다. stale generation이 아니라 방금 지나간 종료이므로 조용히
					// 버리지 않고 map에서 현재(또는 새로 생긴) 상태를 다시 얻어 이어간다.
					continue;
				}
				try {
					if (!roomSessionRegistry.broadcastIfCurrent(command.threadId(), roomGeneration,
							new ChatMessageFrame(command.threadId(), command.from(), command.content()))) {
						closeGeneration(key, state);
						return Optional.empty();
					}
				} catch (RuntimeException ignored) {
					closeGeneration(key, state, true);
					return Optional.of(messageDeliveryFailed(command));
				}

				AiMentionParser.MentionResult mention = AiMentionParser.parse(command.content());
				if (!mention.mentioned()) {
					return Optional.empty();
				}
				if (mention.prompt().isBlank()) {
					return Optional.of(new ErrorFrame(command.threadId(), "MALFORMED_REQUEST",
							"@AI 뒤에 요청 내용을 입력해 주세요.", command.traceId()));
				}

				PendingTurn pendingTurn = new PendingTurn(command.threadId(), roomGeneration, mention.prompt(),
						command.traceId());
				if (state.active != null) {
					if (state.pending.size() >= maxPendingPerRoom) {
						return Optional.of(new ErrorFrame(command.threadId(), "RATE_LIMITED",
								"이 방의 AI 요청 대기열이 가득 찼습니다.", command.traceId()));
					}
					state.pending.addLast(pendingTurn);
					return Optional.empty();
				}

				turnToStart = new ActiveTurn(pendingTurn);
				state.active = turnToStart;
			}

			startTurn(key, state, turnToStart);
			return Optional.empty();
		}
	}

	/** 마지막 연결이 퇴장한 generation의 활성·대기 AI 작업을 즉시 취소한다. */
	public void closeGeneration(UUID threadId, UUID roomGeneration) {
		RoomKey key = new RoomKey(threadId, roomGeneration);
		RoomAiState state = states.get(key);
		if (state != null) {
			closeGeneration(key, state);
		}
	}

	@PreDestroy
	void closeAllGenerations() {
		states.forEach(this::closeGeneration);
	}

	/**
	 * dispatch()가 상태를 얻은 직후, 락을 잡기 전에 개입할 수 있는 테스트 전용 훅이다. 운영 경로는
	 * 아무 것도 하지 않는다 — computeIfAbsent와 synchronized 사이의 경합 창을 결정론적으로
	 * 재현하기 위해서만 테스트 하위 클래스가 재정의한다.
	 */
	void onStateFetchedForTesting() {
	}

	private void startTurn(RoomKey key, RoomAiState state, ActiveTurn activeTurn) {
		Disposable subscription = withTotalDeadline(Flux.defer(() -> llmChatStreamService.streamChat(
				new ChatStreamRequest(activeTurn.turn.threadId(), modelId,
						List.of(new ChatMessage("user", activeTurn.turn.prompt()))))))
				.filter(delta -> !delta.isEmpty())
				.doOnNext(delta -> broadcastDelta(activeTurn, delta))
				.concatWith(Flux.defer(() -> activeTurn.hasNonBlankOutput.get()
						? Flux.empty()
						: Flux.error(new EmptyLlmOutputException())))
				.subscribe(ignored -> {
				}, error -> handleTurnError(key, state, activeTurn, error),
						() -> handleTurnComplete(key, state, activeTurn));

		synchronized (state) {
			if (!state.closed && state.active == activeTurn) {
				activeTurn.subscription.update(subscription);
			} else {
				subscription.dispose();
			}
		}
	}

	private Flux<String> withTotalDeadline(Flux<String> source) {
		return Flux.defer(() -> {
			AtomicBoolean sourceCompleted = new AtomicBoolean();
			return source.doOnComplete(() -> sourceCompleted.set(true))
					.take(turnTimeout, deadlineScheduler)
					.concatWith(Flux.defer(() -> sourceCompleted.get()
							? Flux.empty()
							: Flux.error(new TurnTimeoutException())));
		});
	}

	private void broadcastDelta(ActiveTurn activeTurn, String delta) {
		if (delta.isBlank() && !activeTurn.hasNonBlankOutput.get()) {
			activeTurn.leadingWhitespace.addLast(delta);
			return;
		}
		if (!activeTurn.hasNonBlankOutput.getAndSet(true)) {
			while (!activeTurn.leadingWhitespace.isEmpty()) {
				broadcastStreamingFrame(activeTurn, activeTurn.leadingWhitespace.removeFirst());
			}
		}
		broadcastStreamingFrame(activeTurn, delta);
	}

	private void broadcastStreamingFrame(ActiveTurn activeTurn, String delta) {
		try {
			if (!roomSessionRegistry.broadcastIfCurrent(activeTurn.turn.threadId(), activeTurn.turn.roomGeneration(),
					new ChatAnswerFrame(activeTurn.turn.threadId(), delta, List.of(), false,
							ChatAnswerStatus.STREAMING))) {
				throw new StaleGenerationException();
			}
		} catch (StaleGenerationException error) {
			throw error;
		} catch (RuntimeException error) {
			throw new RoomBroadcastFailureException(error);
		}
	}

	private void handleTurnComplete(RoomKey key, RoomAiState state, ActiveTurn activeTurn) {
		try {
			if (!roomSessionRegistry.broadcastIfCurrent(activeTurn.turn.threadId(), activeTurn.turn.roomGeneration(),
					new ChatAnswerFrame(activeTurn.turn.threadId(), "", List.of(), false, ChatAnswerStatus.DONE))) {
				closeGeneration(key, state);
				return;
			}
		} catch (RuntimeException ignored) {
			closeGeneration(key, state, true);
			return;
		}
		advance(key, state, activeTurn);
	}

	private void handleTurnError(RoomKey key, RoomAiState state, ActiveTurn activeTurn, Throwable error) {
		if (error instanceof StaleGenerationException) {
			closeGeneration(key, state);
			return;
		}
		if (error instanceof RoomBroadcastFailureException) {
			closeGeneration(key, state, true);
			return;
		}

		String code = error instanceof OpenAIServiceException || error instanceof TurnTimeoutException
				|| error instanceof EmptyLlmOutputException ? "MODEL_UNAVAILABLE" : "INTERNAL_ERROR";
		String message = "MODEL_UNAVAILABLE".equals(code) ? "모델을 호출할 수 없습니다." : "AI 응답 처리 중 오류가 발생했습니다.";
		try {
			if (!roomSessionRegistry.broadcastIfCurrent(activeTurn.turn.threadId(), activeTurn.turn.roomGeneration(),
					new ErrorFrame(activeTurn.turn.threadId(), code, message, activeTurn.turn.traceId()))) {
				closeGeneration(key, state);
				return;
			}
		} catch (RuntimeException ignored) {
			closeGeneration(key, state, true);
			return;
		}
		advance(key, state, activeTurn);
	}

	private void advance(RoomKey key, RoomAiState state, ActiveTurn finishedTurn) {
		ActiveTurn nextTurn = null;
		synchronized (state) {
			if (state.closed || state.active != finishedTurn) {
				return;
			}
			PendingTurn next = state.pending.pollFirst();
			if (next == null) {
				state.active = null;
				return;
			}
			nextTurn = new ActiveTurn(next);
			state.active = nextTurn;
		}
		startTurn(key, state, nextTurn);
	}

	private void closeGeneration(RoomKey key, RoomAiState state) {
		closeGeneration(key, state, false);
	}

	private void closeGeneration(RoomKey key, RoomAiState state, boolean notifyPendingCancellation) {
		ActiveTurn activeTurn;
		boolean pendingTurnsCancelled;
		synchronized (state) {
			if (state.closed) {
				return;
			}
			state.closed = true;
			states.remove(key, state);
			pendingTurnsCancelled = !state.pending.isEmpty();
			state.pending.clear();
			activeTurn = state.active;
			state.active = null;
		}
		if (activeTurn != null) {
			activeTurn.subscription.dispose();
		}
		if (notifyPendingCancellation && pendingTurnsCancelled) {
			try {
				roomSessionRegistry.broadcastIfCurrent(key.threadId(), key.roomGeneration(),
						new ErrorFrame(key.threadId(), "MESSAGE_DELIVERY_FAILED",
								"대기 중인 AI 요청이 취소되었습니다.", UUID.randomUUID().toString()));
			} catch (RuntimeException ignored) {
				// The notification uses the failed broadcast path once and is intentionally not retried.
			}
		}
	}

	private static ErrorFrame messageDeliveryFailed(ChatMessageCommand command) {
		return new ErrorFrame(command.threadId(), "MESSAGE_DELIVERY_FAILED", "메시지를 전달하지 못했습니다.",
				command.traceId());
	}

	private record RoomKey(UUID threadId, UUID roomGeneration) {
	}

	private record PendingTurn(UUID threadId, UUID roomGeneration, String prompt, String traceId) {
	}

	private static final class ActiveTurn {

		private final PendingTurn turn;

		private final Disposable.Swap subscription = Disposables.swap();

		private final AtomicBoolean hasNonBlankOutput = new AtomicBoolean();

		private final Deque<String> leadingWhitespace = new ArrayDeque<>();

		ActiveTurn(PendingTurn turn) {
			this.turn = turn;
		}
	}

	private static final class RoomAiState {

		private final Deque<PendingTurn> pending = new ArrayDeque<>();

		private ActiveTurn active;

		private boolean closed;
	}

	private static final class TurnTimeoutException extends RuntimeException {
	}

	private static final class EmptyLlmOutputException extends RuntimeException {
	}

	private static final class StaleGenerationException extends RuntimeException {
	}

	private static final class RoomBroadcastFailureException extends RuntimeException {

		RoomBroadcastFailureException(Throwable cause) {
			super(cause);
		}
	}

}
