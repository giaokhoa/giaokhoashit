package com.giaokhoa.server.organization;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, UUID> {

	List<Course> findByOrderByCreatedAtAscIdAsc();

	List<Course> findByIdInOrderByCreatedAtAscIdAsc(Collection<UUID> ids);

}
