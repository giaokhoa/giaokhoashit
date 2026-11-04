package com.giaokhoa.server.grading;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptItemGradeRepository extends JpaRepository<AttemptItemGrade, UUID> {

	List<AttemptItemGrade> findByAttemptId(UUID attemptId);

	Optional<AttemptItemGrade> findByAttemptIdAndExamItemId(UUID attemptId, UUID examItemId);

}
