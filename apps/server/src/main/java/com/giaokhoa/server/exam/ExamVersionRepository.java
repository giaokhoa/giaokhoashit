package com.giaokhoa.server.exam;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamVersionRepository extends JpaRepository<ExamVersion, UUID> {

	Optional<ExamVersion> findByExamIdAndId(UUID examId, UUID id);

	Optional<ExamVersion> findFirstByExamIdOrderByVersionNumberDesc(UUID examId);

}
