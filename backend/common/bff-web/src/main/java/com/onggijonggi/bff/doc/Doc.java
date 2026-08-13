package com.onggijonggi.bff.doc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Class Name : Doc.java
 * Description : 논리 문서(V5__doc_lifecycle_and_version.sql `doc`). 색인 상태(status 4종)·부서
 *               스코프(dept)·소프트 삭제(deleted_at/by)·현재 검색 버전(cur_ver_id)을 추적한다
 *               (04·DATA 스키마 설계). uploaded_by는 여기 없다 —
 *               버전마다 올린 사람이 다를 수 있어 DocVer로 이동했다.
 *               상태 전이(색인 시작·완료·재시도 등)를 다루는 메서드는 아직 넣지 않는다 — 그걸
 *               호출할 색인 워커가 없다(worker/ 미착수, 투기적 코드 방지).
 */
@Entity
@Table(name = "doc")
public class Doc {

	@Id
	private UUID id;

	/** Keycloak Groups 클레임에서 온 부서 경로 문자열. 조직 전체 문서는 COMMON 센티널을 쓴다. */
	@Column(name = "dept", nullable = false)
	private String department;

	/** admin이 정하는 업무 키. 유니크 범위는 (dept, doc_key)(소프트 삭제 행 제외). */
	@Column(name = "doc_key", nullable = false)
	private String docKey;

	@Column(name = "file_name", nullable = false)
	private String fileName;

	@Column(name = "org_path", nullable = false)
	private String orgPath;

	/** 접근 태그의 유일한 원천. ES 조각의 acc_tag는 여기서 복사된 사본이다. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "acc_tag", nullable = false)
	private List<String> accTag;

	/** 태그가 바뀔 때마다 증가. 조각에도 복사해 정합성 검사 근거로 쓴다. */
	@Column(name = "acc_tag_ver", nullable = false)
	private int accTagVer;

	@Column(name = "emb_mdl", nullable = false)
	private String embMdl;

	@Column(name = "chnk_ver", nullable = false)
	private String chnkVer;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private DocStatus status;

	@Column(name = "status_at", nullable = false)
	private Instant statusAt;

	/** 진행 중인 색인 실행. 런 시작 시 즉시 기록(아직 그 로직 없음 — 컬럼만 존재). */
	@Column(name = "pnd_idx_run_id")
	private UUID pendingIdxRunId;

	/** 마지막으로 성공 확정된 색인 실행. 성공 시에만 갱신. */
	@Column(name = "cur_idx_run_id")
	private UUID curIdxRunId;

	@Column(name = "last_err")
	private String lastErr;

	/** 런 시작마다 +1, 성공·새 파일 재업로드 시 0으로 리셋(아직 그 로직 없음). */
	@Column(name = "att_cnt", nullable = false)
	private int attCnt;

	/** 임베딩 모델의 지문. 이름만으론 같은 태그로 가중치가 바뀌는 걸 못 잡는다. */
	@Column(name = "emb_fgpt")
	private String embFgpt;

	/** 소프트 삭제 시각. null이면 살아있는 문서. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** keycloak_subj 값 복사(FK 아님) — 감사 정보라 계정 삭제에 파괴되면 안 된다. */
	@Column(name = "deleted_by")
	private String deletedBy;

	/**
	 * 지금 검색되는 버전 → doc_ver.id. null이면 "업로드됐지만 아직 한 번도 색인 성공 못 함".
	 * doc → doc_ver → 포인터 UPDATE 순서로 채워지는 순환 참조라 nullable이다.
	 */
	@Column(name = "cur_ver_id")
	private UUID curVerId;

	/** doc_ver.ver_seq 발급 카운터(chat_msg.seq와 동일 메커니즘 — 원자적 UPDATE+RETURNING, 아직 미구현). */
	@Column(name = "next_ver_seq", nullable = false)
	private int nextVerSeq;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Doc() {
	}

	public Doc(String department, String docKey, String fileName, String orgPath,
			List<String> accTag, String embMdl, String chnkVer) {
		this.id = UUID.randomUUID();
		this.department = department;
		this.docKey = docKey;
		this.fileName = fileName;
		this.orgPath = orgPath;
		this.accTag = accTag;
		this.accTagVer = 1;
		this.embMdl = embMdl;
		this.chnkVer = chnkVer;
		this.status = DocStatus.PENDING;
		Instant now = Instant.now();
		this.statusAt = now;
		this.attCnt = 0;
		this.nextVerSeq = 1;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getDepartment() {
		return department;
	}

	public String getDocKey() {
		return docKey;
	}

	public String getFileName() {
		return fileName;
	}

	public String getOrgPath() {
		return orgPath;
	}

	public List<String> getAccTag() {
		return accTag;
	}

	public int getAccTagVer() {
		return accTagVer;
	}

	public String getEmbMdl() {
		return embMdl;
	}

	public String getChnkVer() {
		return chnkVer;
	}

	public DocStatus getStatus() {
		return status;
	}

	public Instant getStatusAt() {
		return statusAt;
	}

	public UUID getPendingIdxRunId() {
		return pendingIdxRunId;
	}

	public UUID getCurIdxRunId() {
		return curIdxRunId;
	}

	public String getLastErr() {
		return lastErr;
	}

	public int getAttCnt() {
		return attCnt;
	}

	public String getEmbFgpt() {
		return embFgpt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public String getDeletedBy() {
		return deletedBy;
	}

	public UUID getCurVerId() {
		return curVerId;
	}

	public int getNextVerSeq() {
		return nextVerSeq;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
