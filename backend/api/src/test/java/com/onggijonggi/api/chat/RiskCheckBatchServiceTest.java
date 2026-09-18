package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Class Name : RiskCheckBatchServiceTest.java
 * Description : RiskCheckBatchService의 스캔·커서 전진·SYSTEM 메시지 생성을 Mockito로 검증한다.
 *               RiskClassifier를 목으로 대체해 실 LLM 없이 판정 분기만 좁혀 본다.
 */
@ExtendWith(MockitoExtension.class)
class RiskCheckBatchServiceTest {

	@Mock
	private ThrRepository thrRepository;

	@Mock
	private MsgRepository msgRepository;

	@Mock
	private ThrRiskCursorRepository thrRiskCursorRepository;

	@Mock
	private RiskClassifier riskClassifier;

	@Mock
	private RoomSessionRegistry roomSessionRegistry;

	private RiskCheckBatchService service;

	@BeforeEach
	void setUp() {
		service = new RiskCheckBatchService(thrRepository, msgRepository, thrRiskCursorRepository, riskClassifier,
				roomSessionRegistry);
	}

	@Test
	void createsASystemMessageWhenARiskyMessageIsFound() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		Msg risky = Msg.human(UUID.randomUUID(), thr.getId(), 1, UUID.randomUUID(), "위험해 보이는 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, -1L))
				.thenReturn(List.of(risky));
		when(riskClassifier.isRisky("위험해 보이는 발화")).thenReturn(true);
		when(thrRepository.allocateNextSeq(thr.getId())).thenReturn(2L);

		service.scanThread(thr);

		ArgumentCaptor<Msg> savedMsg = ArgumentCaptor.forClass(Msg.class);
		verify(msgRepository).save(savedMsg.capture());
		assertThat(savedMsg.getValue().getAthKind()).isEqualTo(AthKind.SYSTEM);
		assertThat(savedMsg.getValue().getSeq()).isEqualTo(2L);

		ArgumentCaptor<ThrRiskCursor> savedCursor = ArgumentCaptor.forClass(ThrRiskCursor.class);
		verify(thrRiskCursorRepository).save(savedCursor.capture());
		assertThat(savedCursor.getValue().getLastSeq()).isEqualTo(1L);

		ArgumentCaptor<SystemNoticeFrame> notice = ArgumentCaptor.forClass(SystemNoticeFrame.class);
		verify(roomSessionRegistry).notifyIfListening(eq(thr.getId()), notice.capture());
		assertThat(notice.getValue().severity()).isEqualTo("warning");
		assertThat(notice.getValue().code()).isEqualTo("RISKY_CONTENT");
	}

	@Test
	void doesNotCreateASystemMessageWhenNothingIsRisky() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		Msg safe = Msg.human(UUID.randomUUID(), thr.getId(), 1, UUID.randomUUID(), "평범한 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, -1L))
				.thenReturn(List.of(safe));
		when(riskClassifier.isRisky("평범한 발화")).thenReturn(false);

		service.scanThread(thr);

		verify(msgRepository, never()).save(any());
		verify(thrRiskCursorRepository).save(any());
		verify(roomSessionRegistry, never()).notifyIfListening(any(), any());
	}

	/** 이미 스캔한 메시지를 다시 검사하지 않는다 — 커서 이후 것만 조회한다. */
	@Test
	void resumesScanningFromTheCursor() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		ThrRiskCursor cursor = new ThrRiskCursor(thr.getId());
		cursor.advanceTo(5L);

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.of(cursor));
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 5L))
				.thenReturn(List.of());

		service.scanThread(thr);

		verify(msgRepository).findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 5L);
		verify(thrRiskCursorRepository, never()).save(any());
	}

	@Test
	void classifiesEveryNewMessageEvenAfterARiskyOne() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		Msg first = Msg.human(UUID.randomUUID(), thr.getId(), 1, UUID.randomUUID(), "위험한 발화");
		Msg second = Msg.human(UUID.randomUUID(), thr.getId(), 2, UUID.randomUUID(), "평범한 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, -1L))
				.thenReturn(List.of(first, second));
		when(riskClassifier.isRisky("위험한 발화")).thenReturn(true);
		when(riskClassifier.isRisky("평범한 발화")).thenReturn(false);
		when(thrRepository.allocateNextSeq(thr.getId())).thenReturn(3L);

		service.scanThread(thr);

		verify(riskClassifier, times(2)).isRisky(any());
		verify(msgRepository, times(1)).save(any());
		ArgumentCaptor<ThrRiskCursor> savedCursor = ArgumentCaptor.forClass(ThrRiskCursor.class);
		verify(thrRiskCursorRepository).save(savedCursor.capture());
		assertThat(savedCursor.getValue().getLastSeq()).isEqualTo(2L);
	}

	/** 스레드 스캔 실패는 그 스레드만 영향받고, 나머지 스레드는 그대로 스캔된다. */
	@Test
	void scanAllThreadsContinuesAfterOneThreadFails() {
		Thr failing = Thr.collab(UUID.randomUUID(), "failing room");
		Thr healthy = Thr.collab(UUID.randomUUID(), "healthy room");

		when(thrRepository.findByKindAndStatusNot(ThrKind.COLLAB, ThrStatus.ARCHIVED))
				.thenReturn(List.of(failing, healthy));
		when(thrRiskCursorRepository.findById(failing.getId())).thenThrow(new RuntimeException("boom"));
		when(thrRiskCursorRepository.findById(healthy.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(eq(healthy.getId()),
				eq(AthKind.HUMAN), eq(-1L))).thenReturn(List.of());

		service.scanAllThreads();

		verify(thrRiskCursorRepository).findById(healthy.getId());
	}

	/**
	 * 커서 없는 새 방에서 seq=0만 있어도(이슈 #234) 위험 검사가 빠지지 않는다 — 회귀 테스트.
	 * lastSeq 기본값이 -1이라 seq > -1 조회가 seq=0을 포함한다.
	 */
	@Test
	void scansTheVeryFirstMessageOfABrandNewRoom() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		Msg risky = Msg.human(UUID.randomUUID(), thr.getId(), 0, UUID.randomUUID(), "위험해 보이는 첫 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, -1L))
				.thenReturn(List.of(risky));
		when(riskClassifier.isRisky("위험해 보이는 첫 발화")).thenReturn(true);
		when(thrRepository.allocateNextSeq(thr.getId())).thenReturn(1L);

		service.scanThread(thr);

		verify(msgRepository).save(any());
		verify(roomSessionRegistry).notifyIfListening(eq(thr.getId()), any());
	}

	/**
	 * 기존(마이그레이션 이전부터 있던) 커서가 있는 방은 다음 스캔에서 seq=0을 소급 검사한다
	 * (이슈 #234). 이 소급 검사는 새 메시지 유무를 보는 조기 return과 독립된 분기라, 새
	 * 메시지가 없는 조용한 방이어도 실행돼야 한다.
	 */
	@Test
	void backfillsSeqZeroForAnExistingCursorEvenWhenTheRoomIsQuiet() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		ThrRiskCursor cursor = new ThrRiskCursor(thr.getId());
		cursor.advanceTo(5L);
		markAsPreMigrationCursor(cursor);
		Msg riskySeqZero = Msg.human(UUID.randomUUID(), thr.getId(), 0, UUID.randomUUID(), "예전 위험 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.of(cursor));
		when(msgRepository.findByThrIdAndAthKindAndSeq(thr.getId(), AthKind.HUMAN, 0L))
				.thenReturn(Optional.of(riskySeqZero));
		when(riskClassifier.isRisky("예전 위험 발화")).thenReturn(true);
		when(thrRepository.allocateNextSeq(thr.getId())).thenReturn(6L);
		// 조용한 방 — 새 메시지가 없다.
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 5L))
				.thenReturn(List.of());

		service.scanThread(thr);

		ArgumentCaptor<Msg> savedMsg = ArgumentCaptor.forClass(Msg.class);
		verify(msgRepository).save(savedMsg.capture());
		assertThat(savedMsg.getValue().getAthKind()).isEqualTo(AthKind.SYSTEM);

		// 오래된 메시지에 대한 백필 발견은 실시간 통지를 보내지 않는다(사용자 확정 사항).
		verify(roomSessionRegistry, never()).notifyIfListening(any(), any());

		ArgumentCaptor<ThrRiskCursor> savedCursor = ArgumentCaptor.forClass(ThrRiskCursor.class);
		verify(thrRiskCursorRepository).save(savedCursor.capture());
		assertThat(savedCursor.getValue().isFrsSeqChc()).isTrue();
		assertThat(savedCursor.getValue().getLastSeq()).isEqualTo(5L);
	}

	/** 한 번 백필된 방은 다시 검사하지 않는다 — 멱등성. */
	@Test
	void doesNotRepeatTheBackfillOnceSeqZeroIsChecked() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		ThrRiskCursor cursor = new ThrRiskCursor(thr.getId());
		cursor.advanceTo(5L);
		// 기본 생성자가 이미 isFrsSeqChc()=true다 — 마이그레이션 이후 새로 만들어진 커서와 동일.

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.of(cursor));
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 5L))
				.thenReturn(List.of());

		service.scanThread(thr);

		verify(msgRepository, never()).findByThrIdAndAthKindAndSeq(any(), any(), anyLong());
		verify(thrRiskCursorRepository, never()).save(any());
	}

	/**
	 * seq=0 메시지가 채번·방송은 됐어도 저장이 실패해 DB에 없을 수 있다(fire-and-forget) —
	 * 이 경우 위험 검사 없이 확인 표시만 남긴다.
	 */
	@Test
	void marksBackfillDoneEvenWhenSeqZeroMessageIsMissing() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		ThrRiskCursor cursor = new ThrRiskCursor(thr.getId());
		cursor.advanceTo(5L);
		markAsPreMigrationCursor(cursor);

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.of(cursor));
		when(msgRepository.findByThrIdAndAthKindAndSeq(thr.getId(), AthKind.HUMAN, 0L)).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 5L))
				.thenReturn(List.of());

		service.scanThread(thr);

		verify(riskClassifier, never()).isRisky(any());
		verify(msgRepository, never()).save(any());
		ArgumentCaptor<ThrRiskCursor> savedCursor = ArgumentCaptor.forClass(ThrRiskCursor.class);
		verify(thrRiskCursorRepository).save(savedCursor.capture());
		assertThat(savedCursor.getValue().isFrsSeqChc()).isTrue();
	}

	/**
	 * "마이그레이션 이전부터 있던 커서"(frsSeqChc=false)를 재현한다 — 생성자는 항상 true로
	 * 시작해(새로 만드는 커서는 수정된 스캔으로 seq=0부터 이미 포함되므로 백필이 필요 없다)
	 * 이 상태를 직접 만들 수 없어, 리플렉션으로 그 필드값만 되돌린다.
	 */
	private static void markAsPreMigrationCursor(ThrRiskCursor cursor) {
		try {
			var field = ThrRiskCursor.class.getDeclaredField("frsSeqChc");
			field.setAccessible(true);
			field.setBoolean(cursor, false);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
