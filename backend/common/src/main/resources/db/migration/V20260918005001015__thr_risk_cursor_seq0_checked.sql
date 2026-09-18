-- 04·DATA — thr_risk_crs에 첫 seq(0) 위험 검사 여부 플래그를 더한다(#234).
--
-- RiskCheckBatchService.scanThread가 커서 없는 방의 lastSeq 기본값을 0으로 두고
-- seq > lastSeq로 조회해, 방의 첫 HUMAN 메시지(seq=0)가 영원히 위험 검사에서 빠지는
-- 결함이 있었다. 이미 존재하는 모든 행은 이 결함의 영향을 받았으므로(그 방의 seq=0은
-- 한 번도 검사되지 않았다) false로 표시해 다음 스캔에서 한 번씩 소급 검사하게 한다.
-- 이 마이그레이션 이후 새로 생성되는 행은 애플리케이션(ThrRiskCursor 생성자)이 true로
-- 명시해 저장한다 — 수정된 코드로 처음 스캔되는 방은 이미 seq=0부터 포함하므로 소급
-- 검사가 필요 없다.
alter table thr_risk_crs add column frs_seq_chc boolean not null default true;

update thr_risk_crs set frs_seq_chc = false;
