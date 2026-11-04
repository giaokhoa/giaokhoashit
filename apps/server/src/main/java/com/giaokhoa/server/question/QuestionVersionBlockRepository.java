package com.giaokhoa.server.question;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionVersionBlockRepository extends JpaRepository<QuestionVersionBlock, UUID> {

	List<QuestionVersionBlock> findByQuestionVersionId(UUID questionVersionId);

	void deleteByQuestionVersionId(UUID questionVersionId);

}
