package com.giaokhoa.server.math;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FormulaBlockRepository extends JpaRepository<FormulaBlock, UUID> {

	List<FormulaBlock> findByQuestionVersionIdOrderByPositionAsc(UUID questionVersionId);

	Optional<FormulaBlock> findByQuestionVersionIdAndBlockId(UUID questionVersionId, String blockId);

	long countByQuestionVersionId(UUID questionVersionId);

}
