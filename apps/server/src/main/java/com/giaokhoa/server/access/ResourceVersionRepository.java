package com.giaokhoa.server.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceVersionRepository extends JpaRepository<ResourceVersion, UUID> {

	List<ResourceVersion> findByResourceIdOrderByVersionNumberAsc(UUID resourceId);

	Optional<ResourceVersion> findByIdAndResourceId(UUID id, UUID resourceId);

}
