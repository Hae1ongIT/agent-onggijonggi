package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrRiskCursor;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.chat.persistence.ThrRiskCursorRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : RiskCheckBatchService.java
 * Description : 위험 발화 사후 검증 배치(#28). 실시간 차단이 아니라 주기적으로 스레드를 스캔해
 *               위험 발화를 감지하면 SYSTEM 메시지로 영속화하고, 그 방에 지금 붙어 있는 사람에게는
 *               system.notice 프레임으로도 통지한다.
 *
 *               둘을 함께 쓰는 이유는 접속자가 없는 방에서 방송만 하면 알림이 사라지기 때문이다
 *               (#27 코멘트) — presence.snapshot 같은 별도 재생 설계 없이 msg 영속화(늦게 들어온
 *               사람은 GET /messages로 본다) + 실시간 통지(지금 접속한 사람은 즉시 본다)를 함께
 *               둔다. 실시간 통지는 RoomSessionRegistry.notifyIfListening으로 하며, 아무도 듣고
 *               있지 않으면 조용히 버려진다 — 배치가 room generation을 모르는 이유는 그 메서드의
 *               설명을 참고한다.
 *
 *               스캔 위치는 msg가 아니라 별도 커서(thr_risk_crs)에 둔다 — 이유는 ThrRiskCursor를
 *               참고한다.
 */
@Service
@ConditionalOnProperty(name = "app.collab.risk-check.enabled", havingValue = "true", matchIfMissing = true)
public class RiskCheckBatchService {

	private static final Logger log = LoggerFactory.getLogger(RiskCheckBatchService.class);

	/** 세분화된 코드 없이 RISKY_CONTENT 하나로 시작한다(#27 코멘트) — system.notice의 code와 같은 값이다. */
	private static final String RISKY_CONTENT_CODE = "RISKY_CONTENT";

	private static final String RISK_NOTICE = "위험할 수 있는 발화가 감지되었습니다.";

	private final ThrRepository thrRepository;

	private final MsgRepository msgRepository;

	private final ThrRiskCursorRepository thrRiskCursorRepository;

	private final RiskClassifier riskClassifier;

	private final RoomSessionRegistry roomSessionRegistry;

	public RiskCheckBatchService(ThrRepository thrRepository, MsgRepository msgRepository,
			ThrRiskCursorRepository thrRiskCursorRepository, RiskClassifier riskClassifier,
			RoomSessionRegistry roomSessionRegistry) {
		this.thrRepository = thrRepository;
		this.msgRepository = msgRepository;
		this.thrRiskCursorRepository = thrRiskCursorRepository;
		this.riskClassifier = riskClassifier;
		this.roomSessionRegistry = roomSessionRegistry;
	}

	/** 스레드 하나의 실패가 나머지 스레드 스캔을 막지 않는다 — 각 스레드를 독립적으로 처리한다. */
	@Scheduled(fixedDelayString = "${app.collab.risk-check.interval:30s}")
	public void scanAllThreads() {
		for (Thr thr : thrRepository.findByKindAndStatusNot(ThrKind.COLLAB, ThrStatus.ARCHIVED)) {
			try {
				scanThread(thr);
			} catch (RuntimeException error) {
				log.error("위험 질문 사후 검증 실패 threadId={}", thr.getId(), error);
			}
		}
	}

	/**
	* 커서가 있고 아직 seq=0을 확인하지 않았으면(이슈 #234) 조기 return과 무관하게 먼저
	* 소급 검사한다 — 새 메시지가 없는 조용한 기존 방도 빠뜨리지 않기 위해서다. 커서를
	* 한 번만 fetch해 두 분기가 같은 인스턴스를 공유하고, 트랜잭션 안에서 최대 한 번만
	* 저장한다.
	*/
	@Transactional
	void scanThread(Thr thr) {
		ThrRiskCursor cursor = thrRiskCursorRepository.findById(thr.getId()).orElse(null);
		boolean cursorChanged = false;

		if (cursor != null && !cursor.isFrsSeqChc()) {
			msgRepository.findByThrIdAndAthKindAndSeq(thr.getId(), AthKind.HUMAN, 0L)
					.ifPresent(seq0 -> {
						if (riskClassifier.isRisky(seq0.getContent())) {
							persistRiskNotice(thr.getId(), false);
						}
					});
			cursor.markFrsSeqChc();
			cursorChanged = true;
		}

		long lastSeq = cursor != null ? cursor.getLastSeq() : -1L;
		List<Msg> newMessages = msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(),
				AthKind.HUMAN, lastSeq);
		if (!newMessages.isEmpty()) {
			for (Msg message : newMessages) {
				if (riskClassifier.isRisky(message.getContent())) {
					persistRiskNotice(thr.getId(), true);
				}
			}
			advanceCursor(cursor, thr.getId(), newMessages.get(newMessages.size() - 1).getSeq());
		} else if (cursorChanged) {
			thrRiskCursorRepository.save(cursor);
		}
	}

	/**
	* notifyLive=false는 소급 백필(이슈 #234)이 발견한 오래된 위험 발화용이다 — 이력에는
	* 남기되, 지금 접속 중인 사람에게 옛 메시지에 대한 실시간 경고를 새삼 띄우지 않는다.
	*/
	private void persistRiskNotice(UUID threadId, boolean notifyLive) {
		long seq = thrRepository.allocateNextSeq(threadId);
		msgRepository.save(Msg.system(threadId, seq, RISK_NOTICE));
		if (notifyLive) {
			roomSessionRegistry.notifyIfListening(threadId,
					new SystemNoticeFrame(threadId, "warning", RISKY_CONTENT_CODE, RISK_NOTICE,
							UUID.randomUUID().toString()));
		}
	}

	/** cursor는 scanThread가 이미 fetch해둔 인스턴스다(없으면 null) — 다시 조회하지 않는다. */
	private void advanceCursor(ThrRiskCursor cursor, UUID threadId, long seq) {
		ThrRiskCursor target = cursor != null ? cursor : new ThrRiskCursor(threadId);
		target.advanceTo(seq);
		thrRiskCursorRepository.save(target);
	}

}
