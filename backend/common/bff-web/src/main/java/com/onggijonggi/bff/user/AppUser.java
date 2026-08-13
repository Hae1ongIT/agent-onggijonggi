package com.onggijonggi.bff.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : AppUser.java
 * Description : Keycloak 인증 사용자에 대응하는 로컬 참조 레코드(V1__app_user_and_doc.sql `app_user`).
 *               역할은 저장하지 않는다 — Keycloak이 부여하고 요청 시점에 태그로 변환한다(04·DATA).
 */
@Entity
@Table(name = "app_user")
public class AppUser {

	/** 내부 식별자. JIT 프로비저닝 시 서버가 새로 발급(UUID.randomUUID()). */
	@Id
	private UUID id;

	/** Keycloak JWT sub 클레임. unique — 같은 사용자가 두 행으로 중복 생성되지 않게 막는다. */
	@Column(name = "keycloak_subj", nullable = false, unique = true)
	private String keycloakSubj;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AppUser() {
	}

	public AppUser(String keycloakSubj) {
		this.id = UUID.randomUUID();
		this.keycloakSubj = keycloakSubj;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getKeycloakSubj() {
		return keycloakSubj;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
