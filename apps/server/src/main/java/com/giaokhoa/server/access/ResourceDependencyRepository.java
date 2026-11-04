package com.giaokhoa.server.access;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceDependencyRepository extends JpaRepository<ResourceDependency, UUID> {
}
