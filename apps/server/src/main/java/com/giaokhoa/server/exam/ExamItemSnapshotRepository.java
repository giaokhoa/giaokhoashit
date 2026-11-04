package com.giaokhoa.server.exam;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamItemSnapshotRepository extends JpaRepository<ExamItemSnapshot, UUID> {

	List<ExamItemSnapshot> findByExamVersionIdOrderByPositionAsc(UUID examVersionId);

}
