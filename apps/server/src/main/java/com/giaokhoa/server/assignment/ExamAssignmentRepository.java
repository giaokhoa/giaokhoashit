package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamAssignmentRepository extends JpaRepository<ExamAssignment, UUID> {

	List<ExamAssignment> findByClassIdIn(Collection<UUID> classIds);

	List<ExamAssignment> findByClassIdInAndOpensAtLessThanEqual(Collection<UUID> classIds, OffsetDateTime moment);

}
