package com.giaokhoa.server.profile;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCustomFieldValueRepository extends JpaRepository<UserCustomFieldValue, UserCustomFieldValue.Id> {

	List<UserCustomFieldValue> findByIdUserId(UUID userId);

}
