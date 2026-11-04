package com.giaokhoa.server.files;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileLinkRepository extends JpaRepository<FileLink, UUID> {

	List<FileLink> findByResourceVersionIdOrderByCreatedAtAsc(UUID resourceVersionId);

	List<FileLink> findByBlobId(UUID blobId);

	Optional<FileLink> findBySessionId(UUID sessionId);

}
