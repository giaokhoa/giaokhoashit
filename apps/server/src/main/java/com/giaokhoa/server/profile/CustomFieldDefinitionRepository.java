package com.giaokhoa.server.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomFieldDefinitionRepository extends JpaRepository<CustomFieldDefinition, UUID> {

	Optional<CustomFieldDefinition> findByFieldKey(String fieldKey);

	List<CustomFieldDefinition> findAllByOrderByFieldKeyAsc();

}
