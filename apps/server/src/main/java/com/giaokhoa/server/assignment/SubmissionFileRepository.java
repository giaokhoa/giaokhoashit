package com.giaokhoa.server.assignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionFileRepository extends JpaRepository<SubmissionFile, UUID> {

	List<SubmissionFile> findByAttemptIdOrderByCreatedAtAsc(UUID attemptId);

	Optional<SubmissionFile> findByAttemptIdAndUploadSessionId(UUID attemptId, UUID uploadSessionId);

}
