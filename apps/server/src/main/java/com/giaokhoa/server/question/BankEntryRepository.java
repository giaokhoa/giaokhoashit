package com.giaokhoa.server.question;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankEntryRepository extends JpaRepository<BankEntry, UUID> {

	Optional<BankEntry> findByBankIdAndQuestionVersionId(UUID bankId, UUID questionVersionId);

	Optional<BankEntry> findByIdAndBankId(UUID id, UUID bankId);

}
