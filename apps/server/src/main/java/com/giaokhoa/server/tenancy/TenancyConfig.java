package com.giaokhoa.server.tenancy;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.cfg.MultiTenancySettings;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Lắp các mảnh của khung tenancy vào Spring/Hibernate: resolver tenant id của Hibernate đọc từ {@link TenantContext}
 * của request, và transaction manager của ứng dụng là {@link TenantTransactionManager} để mọi giao dịch đều được đặt
 * biến ngữ cảnh RLS tại một điểm chặn duy nhất.
 */
@Configuration
public class TenancyConfig {

	@Bean
	HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer() {
		TenantIdentifierResolver resolver = new TenantIdentifierResolver();
		return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
	}

	@Bean
	PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
		return new TenantTransactionManager(entityManagerFactory);
	}

}
