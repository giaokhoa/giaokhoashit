package com.giaokhoa.server.exam;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamItemRepository extends JpaRepository<ExamItem, UUID> {

	List<ExamItem> findByExamVersionIdOrderByPositionAsc(UUID examVersionId);

	void deleteByExamVersionId(UUID examVersionId);

}
