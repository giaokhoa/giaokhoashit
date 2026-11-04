package com.giaokhoa.server.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassMemberRepository extends JpaRepository<ClassMember, UUID> {

	Optional<ClassMember> findByClassIdAndStudentSub(UUID classId, String studentSub);

	List<ClassMember> findByClassIdAndRemovedAtIsNullOrderByCreatedAtAscIdAsc(UUID classId);

	List<ClassMember> findByStudentSubAndRemovedAtIsNull(String studentSub);

	boolean existsByClassIdAndStudentSubAndRemovedAtIsNull(UUID classId, String studentSub);

}
