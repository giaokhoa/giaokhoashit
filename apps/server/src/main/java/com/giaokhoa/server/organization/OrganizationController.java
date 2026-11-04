package com.giaokhoa.server.organization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.giaokhoa.server.organization.OrganizationService.CourseView;
import com.giaokhoa.server.organization.OrganizationService.MemberView;
import com.giaokhoa.server.organization.OrganizationService.TeachingClassView;

@RestController
@RequestMapping("/api")
public class OrganizationController {

	private final OrganizationService service;

	public OrganizationController(OrganizationService service) {
		this.service = service;
	}

	@PostMapping("/courses")
	public ResponseEntity<CourseView> createCourse(@RequestBody CreateCourseRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.createCourse(request.name()));
	}

	@PostMapping("/classes")
	public ResponseEntity<TeachingClassView> createClass(@RequestBody CreateClassRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.service.createClass(request.name(), request.courseId()));
	}

	@GetMapping("/courses")
	public List<CourseView> listCourses() {
		return this.service.listCourses();
	}

	@GetMapping("/classes")
	public List<TeachingClassView> listClasses() {
		return this.service.listClasses();
	}

	@GetMapping("/classes/{classId}")
	public TeachingClassView getClass(@PathVariable UUID classId) {
		return this.service.getClass(classId);
	}

	@GetMapping("/classes/{classId}/members")
	public List<MemberView> listMembers(@PathVariable UUID classId) {
		return this.service.listMembers(classId);
	}

	@PutMapping("/classes/{classId}/teacher")
	public TeachingClassView assignTeacher(@PathVariable UUID classId,
			@RequestBody AssignTeacherRequest request) {
		return this.service.assignTeacher(classId, request.teacherSub());
	}

	@PostMapping("/classes/{classId}/members")
	public ResponseEntity<MemberView> enroll(@PathVariable UUID classId,
			@RequestBody EnrollMemberRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.enroll(classId, request.studentSub()));
	}

	@DeleteMapping("/classes/{classId}/members/{studentSub}")
	public ResponseEntity<Void> removeMember(@PathVariable UUID classId, @PathVariable String studentSub) {
		this.service.removeMember(classId, studentSub);
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(OrganizationErrors.OrgException.class)
	public ResponseEntity<Map<String, String>> handleOrgException(OrganizationErrors.OrgException exception) {
		return ResponseEntity.status(exception.status())
			.body(Map.of("code", exception.code(), "message", exception.getMessage()));
	}

	public record CreateCourseRequest(String name) {
	}

	public record CreateClassRequest(String name, UUID courseId) {
	}

	public record AssignTeacherRequest(String teacherSub) {
	}

	public record EnrollMemberRequest(String studentSub) {
	}

}
