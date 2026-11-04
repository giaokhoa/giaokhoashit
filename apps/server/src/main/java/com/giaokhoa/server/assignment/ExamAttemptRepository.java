package com.giaokhoa.server.assignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, UUID> {

	Optional<ExamAttempt> findByAssignmentIdAndStudentSub(UUID assignmentId, String studentSub);

	List<ExamAttempt> findByAssignmentId(UUID assignmentId);

	boolean existsByAssignmentId(UUID assignmentId);

}
