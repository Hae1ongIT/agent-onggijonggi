package com.onggijonggi.bff.doc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : DocVer.java
 * Description : 문서 버전(업로드) 이력(V5__doc_lifecycle_and_version.sql `doc_ver`). 색인 상태는
 *               Doc에 남고, 이 엔티티는 순수하게 "업로드된 파일의 이력"만 담는다
 *               (04·DATA 스키마 설계). docId는 Doc과 연관관계 매핑
 *               없이 평문 컬럼으로 둔다(ChatSess가 AppUser를 참조하는 것과 동일한 관례).
 */
@Entity
@Table(name = "doc_ver")
public class DocVer {

	@Id
	private UUID id;

	@Column(name = "doc_id", nullable = false)
	private UUID docId;

	/** 문서 내 단조 증가. doc.next_ver_seq로 원자적 발급(아직 그 로직 없음 — 필드만 존재). */
	@Column(name = "ver_seq", nullable = false)
	private int verSeq;

	@Column(name = "file_name", nullable = false)
	private String fileName;

	@Column(name = "org_path", nullable = false)
	private String orgPath;

	/** keycloak_subj 값 복사(FK 아님) — deletedBy와 같은 이유로 계정 삭제에 파괴되면 안 된다. */
	@Column(name = "uploaded_by", nullable = false)
	private String uploadedBy;

	@Column(name = "uploaded_at", nullable = false)
	private Instant uploadedAt;

	protected DocVer() {
	}

	public DocVer(UUID docId, int verSeq, String fileName, String orgPath, String uploadedBy) {
		this.id = UUID.randomUUID();
		this.docId = docId;
		this.verSeq = verSeq;
		this.fileName = fileName;
		this.orgPath = orgPath;
		this.uploadedBy = uploadedBy;
		this.uploadedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getDocId() {
		return docId;
	}

	public int getVerSeq() {
		return verSeq;
	}

	public String getFileName() {
		return fileName;
	}

	public String getOrgPath() {
		return orgPath;
	}

	public String getUploadedBy() {
		return uploadedBy;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

}
