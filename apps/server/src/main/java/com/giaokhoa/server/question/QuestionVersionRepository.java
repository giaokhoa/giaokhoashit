package com.giaokhoa.server.question;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, UUID> {

	List<QuestionVersion> findByQuestionIdOrderByVersionNumberAsc(UUID questionId);

	Optional<QuestionVersion> findFirstByQuestionIdOrderByVersionNumberDesc(UUID questionId);

	Optional<QuestionVersion> findByIdAndQuestionId(UUID id, UUID questionId);

}
