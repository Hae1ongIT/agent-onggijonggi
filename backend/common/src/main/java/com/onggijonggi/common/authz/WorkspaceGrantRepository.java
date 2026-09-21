package com.onggijonggi.common.authz;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : WorkspaceGrantRepository.java
 * Description : wrk_grn 레포지토리.
 */
public interface WorkspaceGrantRepository extends JpaRepository<WorkspaceGrant, UUID> {

	Optional<WorkspaceGrant> findByTenantIdAndOrgUnitIdAndRoleAndWorkspaceNodeId(
			UUID tenantId, UUID orgUnitId, WorkspaceRole role, UUID workspaceNodeId);

	List<WorkspaceGrant> findByTenantId(UUID tenantId);
}
