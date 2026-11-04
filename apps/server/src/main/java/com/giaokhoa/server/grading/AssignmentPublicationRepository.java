package com.giaokhoa.server.grading;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentPublicationRepository extends JpaRepository<AssignmentPublication, UUID> {

}
