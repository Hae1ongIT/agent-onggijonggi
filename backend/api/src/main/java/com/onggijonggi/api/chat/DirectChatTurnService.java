package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : DirectChatTurnService.java
 * Description : HTTP 1:1 발화를 공용 thr/msg에 저장한다. 새 DIRECT Thread·OWNER 참여·HUMAN/AGENT
 *               두 메시지를 하나의 트랜잭션으로 만들고, 기존 Thread에서는 행 잠금으로 두 seq를 함께
 *               예약한다. 클라이언트 sessionId 충돌 뒤의 재조회는 호출자(PersistingChatStreamService)가
 *               이 서비스의 기존 Thread 경로를 다시 호출해 처리한다.
 */
@Service
public class DirectChatTurnService {

	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final MsgRepository msgRepository;

	public DirectChatTurnService(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository,
			MsgRepository msgRepository) {
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.msgRepository = msgRepository;
	}

	@Transactional
	public StoredTurn prepareOrCreateBlocking(UUID threadId, UUID userId, String content, String title) {
		return thrRepository.findByIdForSeqUpdate(threadId)
				.map(thread -> appendToExisting(thread, userId, content))
				.orElseGet(() -> create(threadId, userId, content, title));
	}

	/** 새 DIRECT 생성의 PK 경합 뒤에는 이 경로만 재시도한다. 타인·COLLAB은 모두 404다. */
	@Transactional
	public StoredTurn prepareExistingBlocking(UUID threadId, UUID userId, String content) {
		Thr thread = thrRepository.findByIdForSeqUpdate(threadId).orElseThrow(DirectChatTurnService::notFound);
		return appendToExisting(thread, userId, content);
	}

	@Transactional
	public void persistCompletedAgentReplyBlocking(StoredTurn turn, String content) {
		msgRepository.save(Msg.completedAgent(turn.agentMessageId(), turn.threadId(), turn.agentSeq(), content));
	}

	private StoredTurn create(UUID threadId, UUID userId, String content, String title) {
		Thr thread = thrRepository.save(Thr.direct(threadId, userId, title));
		ThrMbr owner = thrMbrRepository.save(new ThrMbr(threadId, userId, ThrMbrRole.OWNER, userId));
		return persistTurn(thread, owner, content);
	}

	private StoredTurn appendToExisting(Thr thread, UUID userId, String content) {
		if (thread.getKind() != ThrKind.DIRECT || !userId.equals(thread.getDrcOwnUserId())) {
			throw notFound();
		}
		ThrMbr owner = thrMbrRepository.findByThrIdAndUserIdAndStatus(thread.getId(), userId, ThrMbrStatus.ACTIVE)
				.orElseThrow(DirectChatTurnService::notFound);
		return persistTurn(thread, owner, content);
	}

	private StoredTurn persistTurn(Thr thread, ThrMbr owner, String content) {
		long firstSeq = thread.reserveSeqBlock(2);
		msgRepository.save(Msg.human(UUID.randomUUID(), thread.getId(), firstSeq, owner.getId(), content));
		return new StoredTurn(UUID.randomUUID(), thread.getId(), firstSeq + 1);
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	/** 스트림 완료 시 어느 PENDING AGENT 메시지를 COMPLETE로 닫을지 caller에게 전달한다. */
	public record StoredTurn(UUID agentMessageId, UUID threadId, long agentSeq) {
	}

}
