package com.giaokhoa.server.access;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessResourceRepository extends JpaRepository<AccessResource, UUID> {
}
