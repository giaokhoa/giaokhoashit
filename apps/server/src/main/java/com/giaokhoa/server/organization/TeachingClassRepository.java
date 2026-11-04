package com.giaokhoa.server.organization;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TeachingClassRepository extends JpaRepository<TeachingClass, UUID> {

	List<TeachingClass> findByOrderByCreatedAtAscIdAsc();

	List<TeachingClass> findByTeacherSubOrderByCreatedAtAscIdAsc(String teacherSub);

	List<TeachingClass> findByIdInOrderByCreatedAtAscIdAsc(Collection<UUID> ids);

}
