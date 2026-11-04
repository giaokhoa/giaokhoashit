package com.giaokhoa.server.question;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

	Optional<Question> findByIdAndOwnerSub(UUID id, String ownerSub);

}
