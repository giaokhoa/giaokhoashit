package com.giaokhoa.server.question;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionBankRepository extends JpaRepository<QuestionBank, UUID> {

	List<QuestionBank> findByOwnerSubOrderByCreatedAtAsc(String ownerSub);

	Optional<QuestionBank> findByIdAndOwnerSub(UUID id, String ownerSub);

}
