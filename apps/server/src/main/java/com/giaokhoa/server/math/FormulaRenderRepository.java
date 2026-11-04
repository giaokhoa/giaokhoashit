package com.giaokhoa.server.math;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FormulaRenderRepository extends JpaRepository<FormulaRender, UUID> {

	Optional<FormulaRender> findByFormulaBlockIdAndSourceRevision(UUID formulaBlockId, int sourceRevision);

	List<FormulaRender> findByFormulaBlockIdOrderBySourceRevisionDesc(UUID formulaBlockId);

}
