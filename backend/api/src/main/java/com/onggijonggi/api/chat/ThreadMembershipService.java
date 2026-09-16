package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadMembershipService.java
 * Description : Thread 참가 여부·쓰기 가능 여부 조회. 테이블 매핑은 04·DATA 계층에 있고 이 판정만
 *               03·CORE에 둔다 — 참가자 판정 경계는 이슈 #22를, LOCKED·ARCHIVED가 쓰기를 막는
 *               경계는 이슈 #131을 확인한다. JPA는 블로킹이라 boundedElastic으로 오프로딩한다.
 */
@Service
public class ThreadMembershipService {

	private final ThrMbrRepository thrMbrRepository;

	private final ThrRepository thrRepository;

	public ThreadMembershipService(ThrMbrRepository thrMbrRepository, ThrRepository thrRepository) {
		this.thrMbrRepository = thrMbrRepository;
		this.thrRepository = thrRepository;
	}

	/** 끝난 참가 행이 같은 (thr_id, user_id)로 남아 있어서, 활성 행만 참가로 센다. */
	public Mono<Boolean> isActiveParticipant(UUID threadId, UUID userId) {
		return Mono.fromCallable(() ->
						thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, userId, ThrMbrStatus.ACTIVE))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** 기존 협업 이력 별칭만 쓰는 판정이다. 일반 참가 판정은 DIRECT WS 전환을 위해 제한하지 않는다. */
	public Mono<Boolean> isActiveCollabParticipant(UUID threadId, UUID userId) {
		return Mono.fromCallable(() ->
						thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, userId, ThrMbrStatus.ACTIVE)
								&& thrRepository.existsByIdAndKind(threadId, ThrKind.COLLAB))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** LOCKED·ARCHIVED로 바뀐 방은 ACTIVE가 아니므로 새 메시지·초대 같은 쓰기 작업을 막는다(#131). */
	public Mono<Boolean> isOpenForWriting(UUID threadId) {
		return Mono.fromCallable(() -> thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** dispatcher가 DIRECT·COLLAB 분기를 결정하는 데 쓴다(이슈 #162). 방이 없으면 empty. */
	public Mono<Optional<ThrKind>> kindOf(UUID threadId) {
		return Mono.fromCallable(() -> thrRepository.findById(threadId).map(thr -> thr.getKind()))
				.subscribeOn(Schedulers.boundedElastic());
	}

}
