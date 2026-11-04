package com.giaokhoa.server.access;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionItemRepository extends JpaRepository<SubmissionItem, UUID> {

	List<SubmissionItem> findBySubmissionId(UUID submissionId);

}
