package com.giaokhoa.server;

import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContextFilter;
import com.giaokhoa.server.tenancy.TenantContextResolver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, MembershipChecker membershipChecker) throws Exception {
		http
				// API chỉ nhận Bearer token, không dùng session hay cookie nên tắt CSRF và ép stateless.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// Health phải mở không cần token để hạ tầng thăm dò được tình trạng ứng dụng.
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
				// Ngữ cảnh tenant chỉ dựng được sau khi JWT đã qua cổng kiểm chứng, nên filter ngữ cảnh đứng ngay
				// sau filter xác thực Bearer.
				.addFilterAfter(new TenantContextFilter(new TenantContextResolver(membershipChecker)),
						BearerTokenAuthenticationFilter.class);
		return http.build();
	}

}
