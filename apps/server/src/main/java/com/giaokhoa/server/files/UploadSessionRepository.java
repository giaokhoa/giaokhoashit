package com.giaokhoa.server.files;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

	List<UploadSession> findByStatusInAndExpiresAtBefore(List<String> statuses, OffsetDateTime expiresAt);

}
