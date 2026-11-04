package com.giaokhoa.server.access;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceGrantRepository extends JpaRepository<ResourceGrant, UUID> {

	List<ResourceGrant> findByResourceId(UUID resourceId);

	List<ResourceGrant> findByGranteeUserSub(String granteeUserSub);

	List<ResourceGrant> findByGranteeOrganizationId(String granteeOrganizationId);

}
