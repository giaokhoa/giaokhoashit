package com.giaokhoa.server.tenancy;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt kiểm chứng của khung tenancy, không phải endpoint nghiệp vụ: phơi bày {@link TenantContext} đã resolve và
 * đường ghi/đọc {@link TenantRecord} tối thiểu để nghiệm thu ngữ cảnh và cách ly trên đường runtime thật.
 */
@RestController
@RequestMapping("/api/tenancy-check")
public class TenancyCheckController {

	private final TenancyCheckService service;

	public TenancyCheckController(TenancyCheckService service) {
		this.service = service;
	}

	@GetMapping("/context")
	TenantContext context() {
		return TenantContexts.current();
	}

	@PostMapping("/records")
	TenancyCheckService.RecordView createRecord(@RequestBody CreateRecordRequest request) {
		return this.service.createRecord(request.content());
	}

	@GetMapping("/records")
	List<TenancyCheckService.RecordView> listRecords() {
		return this.service.listRecords();
	}

	record CreateRecordRequest(String content) {
	}

}
