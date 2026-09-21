package com.onggijonggi.common.authz;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : AuthorizationAuditRepository.java
 * Description : authz_adt 레포지토리. 행은 수정·삭제할 수 없다(DB trigger가 거부한다).
 */
public interface AuthorizationAuditRepository extends JpaRepository<AuthorizationAudit, UUID> {

	/** reconcile이 이 Tenant에 같은 배포 ID로 이미 적용됐는지 — 같은 배포 ID는 Tenant마다 한 번만 적용한다. */
	boolean existsByTenantIdAndDeploymentId(UUID tenantId, String deploymentId);

	List<AuthorizationAudit> findByTenantIdOrderByCreatedAtAscIdAsc(UUID tenantId);

	Optional<AuthorizationAudit> findFirstByTenantIdOrderByCreatedAtDescIdDesc(UUID tenantId);
}
