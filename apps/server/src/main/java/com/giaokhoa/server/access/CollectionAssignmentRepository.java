package com.giaokhoa.server.access;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionAssignmentRepository extends JpaRepository<CollectionAssignment, UUID> {

	List<CollectionAssignment> findByCollectionIdAndResourceTypeAndAssigneeSubAndRevokedAtIsNull(UUID collectionId,
			String resourceType, String assigneeSub);

}
