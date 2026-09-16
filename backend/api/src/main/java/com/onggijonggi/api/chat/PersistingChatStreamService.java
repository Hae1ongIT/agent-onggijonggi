package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : PersistingChatStreamService.java
 * Description : ChatStreamService 데코레이터(03-CORE). HTTP 1:1의 URL·text/plain 응답·전체 이력
 *               요청은 유지하되, 요청 마지막 HUMAN과 응답 AGENT는 chat_sess/chat_msg가 아닌
 *               DIRECT thr/msg에 저장한다. 저장소 내부 오류는 기존처럼 스트림을 막지 않지만, 타인·
 *               COLLAB Thread 접근은 LLM 호출 전에 404로 끝낸다.
 */
@Service
@Primary
public class PersistingChatStreamService implements ChatStreamService {

	private static final Logger log = LoggerFactory.getLogger(PersistingChatStreamService.class);

	private static final int TITLE_MAX_LENGTH = 50;

	private final LlmChatStreamService delegate;
	private final CurrentActorProvider currentActorProvider;
	private final DirectChatTurnService directChatTurnService;

	public PersistingChatStreamService(
			LlmChatStreamService delegate,
			CurrentActorProvider currentActorProvider,
			DirectChatTurnService directChatTurnService) {
		this.delegate = delegate;
		this.currentActorProvider = currentActorProvider;
		this.directChatTurnService = directChatTurnService;
	}

	@Override
	public Flux<String> streamChat(ChatStreamRequest request) {
		return currentActorProvider.currentActor()
				.map(CurrentActor::userId)
				.flatMap(userId -> persistUserTurn(request, userId))
				.onErrorResume(error -> {
					if (error instanceof ResponseStatusException) {
						return Mono.error(error);
					}
					log.error("1:1 Thread/메시지 저장 실패, 채팅 스트림은 계속합니다. sessionId={}", request.sessionId(),
							error);
					return Mono.just(Optional.empty());
				})
				.flatMapMany(turn -> streamAndPersistReply(request, turn));
	}

	private Mono<Optional<DirectChatTurnService.StoredTurn>> persistUserTurn(ChatStreamRequest request, UUID userId) {
		String latestUserContent = latestUserMessageContent(request);
		return Mono.fromCallable(() -> directChatTurnService.prepareOrCreateBlocking(request.sessionId(), userId,
					latestUserContent, titleFor(latestUserContent)))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(DataIntegrityViolationException.class, error -> Mono
						.fromCallable(() -> directChatTurnService.prepareExistingBlocking(request.sessionId(), userId,
								latestUserContent))
						.subscribeOn(Schedulers.boundedElastic()))
				.map(Optional::of);
	}

	private String titleFor(String content) {
		String trimmed = content.trim();
		return trimmed.length() > TITLE_MAX_LENGTH ? trimmed.substring(0, TITLE_MAX_LENGTH) : trimmed;
	}

	private String latestUserMessageContent(ChatStreamRequest request) {
		List<ChatMessage> messages = request.messages();
		return messages.get(messages.size() - 1).content();
	}

	private Flux<String> streamAndPersistReply(ChatStreamRequest request,
			Optional<DirectChatTurnService.StoredTurn> turn) {
		StringBuilder buffer = new StringBuilder();
		return delegate.streamChat(request)
				.doOnNext(buffer::append)
				.doOnComplete(() -> turn.ifPresent(storedTurn ->
						persistAssistantReply(storedTurn, buffer.toString())));
	}

	/** 응답 저장 실패는 이미 시작한 HTTP 응답을 깨지 않도록 별도 boundedElastic 체인에서 기록만 한다. */
	private void persistAssistantReply(DirectChatTurnService.StoredTurn turn, String content) {
		Mono.fromRunnable(() -> directChatTurnService.persistCompletedAgentReplyBlocking(turn, content))
				.subscribeOn(Schedulers.boundedElastic())
				.doOnError(error -> log.error("1:1 assistant 저장 실패. agentMessageId={}", turn.agentMessageId(), error))
				.onErrorComplete()
				.subscribe();
	}

}
