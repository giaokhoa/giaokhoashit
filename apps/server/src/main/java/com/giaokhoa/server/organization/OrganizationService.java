package com.giaokhoa.server.organization;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

@Service
public class OrganizationService {

	private static final String ADMIN_ROLE = "tenant-admin";

	private final CourseRepository courses;

	private final TeachingClassRepository classes;

	private final ClassMemberRepository members;

	private final MembershipChecker membershipChecker;

	private final OrganizationDirectory directory;

	private final TeachingAccessChecks accessChecks;

	private final GuestPartitionScope guestScope;

	private final JdbcTemplate jdbc;

	public OrganizationService(CourseRepository courses, TeachingClassRepository classes,
			ClassMemberRepository members, MembershipChecker membershipChecker, OrganizationDirectory directory,
			TeachingAccessChecks accessChecks, GuestPartitionScope guestScope, JdbcTemplate jdbc) {
		this.courses = courses;
		this.classes = classes;
		this.members = members;
		this.membershipChecker = membershipChecker;
		this.directory = directory;
		this.accessChecks = accessChecks;
		this.guestScope = guestScope;
		this.jdbc = jdbc;
	}

	@Transactional
	public CourseView createCourse(String name) {
		TenantContext context = requireCenterAdmin();
		requireName(name);
		Course course = new Course();
		course.setId(UUID.randomUUID());
		course.setName(name);
		course.setCreatedBySub(context.sub());
		OffsetDateTime now = OffsetDateTime.now();
		course.setCreatedAt(now);
		course.setUpdatedAt(now);
		return CourseView.of(this.courses.save(course));
	}

	@Transactional
	public TeachingClassView createClass(String name, UUID courseId) {
		TenantContext context = requireCenterAdmin();
		requireName(name);
		// findById chạy dưới discriminator của phân vùng hiện tại: khoá học của trung tâm khác không hiện ra ở
		// đây và cùng rơi vào nhánh từ chối với khoá học không tồn tại, đúng ý nghĩa "không thuộc trung tâm này".
		if (courseId != null && this.courses.findById(courseId).isEmpty()) {
			throw OrganizationErrors.validation("courseId không thuộc trung tâm hiện tại hoặc không tồn tại");
		}
		TeachingClass teachingClass = new TeachingClass();
		teachingClass.setId(UUID.randomUUID());
		teachingClass.setName(name);
		teachingClass.setCourseId(courseId);
		teachingClass.setCreatedBySub(context.sub());
		OffsetDateTime now = OffsetDateTime.now();
		teachingClass.setCreatedAt(now);
		teachingClass.setUpdatedAt(now);
		return TeachingClassView.of(this.classes.save(teachingClass));
	}

	@Transactional(readOnly = true)
	public List<CourseView> listCourses() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			return List.of();
		}
		if (context.guest()) {
			return guestCourses(context);
		}
		if (context.organizationId() == null) {
			return List.of();
		}
		if (isAdmin(context)) {
			return this.courses.findByOrderByCreatedAtAscIdAsc().stream().map(CourseView::of).toList();
		}
		Set<UUID> courseIds = new LinkedHashSet<>();
		for (TeachingClass visible : visibleClassesForMember(context)) {
			if (visible.getCourseId() != null) {
				courseIds.add(visible.getCourseId());
			}
		}
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return this.courses.findByIdInOrderByCreatedAtAscIdAsc(courseIds).stream().map(CourseView::of).toList();
	}

	@Transactional(readOnly = true)
	public List<TeachingClassView> listClasses() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			return List.of();
		}
		if (context.guest()) {
			return guestClasses(context);
		}
		if (context.organizationId() == null) {
			return List.of();
		}
		if (isAdmin(context)) {
			return this.classes.findByOrderByCreatedAtAscIdAsc().stream().map(TeachingClassView::of).toList();
		}
		return visibleClassesForMember(context).stream().map(TeachingClassView::of).toList();
	}

	@Transactional(readOnly = true)
	public TeachingClassView getClass(UUID classId) {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw OrganizationErrors.forbidden();
		}
		if (context.guest()) {
			return guestClassDetail(context, classId);
		}
		if (context.organizationId() == null) {
			throw OrganizationErrors.forbidden();
		}
		TeachingClass teachingClass = this.classes.findById(classId).orElse(null);
		// Lớp ngoài tầm nhìn và lớp không tồn tại cùng nhận một từ chối 403 không kèm dữ kiện: phân biệt hai
		// trường hợp sẽ lộ sự tồn tại của lớp thuộc trung tâm cho người không có quyền thấy nó.
		if (teachingClass == null) {
			throw OrganizationErrors.forbidden();
		}
		if (isAdmin(context) || this.accessChecks.isAssignedTeacher(teachingClass)
				|| this.members.existsByClassIdAndStudentSubAndRemovedAtIsNull(classId, context.sub())) {
			return TeachingClassView.of(teachingClass);
		}
		throw OrganizationErrors.forbidden();
	}

	@Transactional(readOnly = true)
	public List<MemberView> listMembers(UUID classId) {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw OrganizationErrors.forbidden();
		}
		if (context.guest() || context.organizationId() == null) {
			throw OrganizationErrors.forbidden();
		}
		TeachingClass teachingClass = this.classes.findById(classId).orElseThrow(OrganizationErrors::notFound);
		if (!isAdmin(context) && !this.accessChecks.isAssignedTeacher(teachingClass)) {
			throw OrganizationErrors.forbidden();
		}
		return this.members.findByClassIdAndRemovedAtIsNullOrderByCreatedAtAscIdAsc(classId).stream()
			.map(MemberView::of)
			.toList();
	}

	@Transactional
	public TeachingClassView assignTeacher(UUID classId, String teacherSub) {
		TenantContext context = requireCenterAdmin();
		if (teacherSub == null || teacherSub.isBlank()) {
			throw OrganizationErrors.validation("teacherSub không được để trống");
		}
		TeachingClass teachingClass = this.classes.findById(classId).orElseThrow(OrganizationErrors::notFound);
		// Đặt giáo viên là quyết định quyền thật nên cả hai điều kiện đều đọc dữ kiện hiện hành tại Keycloak tại
		// đúng thời điểm này, không tin dữ kiện cũ trong token của bất kỳ ai; không xác minh được thì dừng ở 503.
		switch (this.membershipChecker.check(context.organizationId(), teacherSub)) {
			case NOT_MEMBER -> throw OrganizationErrors
				.validation("Người được đặt không phải thành viên hiện hành của trung tâm");
			case UNVERIFIABLE -> throw OrganizationErrors.membershipUnverifiable();
			case MEMBER -> {
			}
		}
		switch (this.directory.checkTeacherRole(UUID.fromString(context.organizationId()), teacherSub)) {
			case NOT_TEACHER -> throw OrganizationErrors
				.validation("Người được đặt không giữ vai trò giáo viên tại trung tâm");
			case UNVERIFIABLE -> throw OrganizationErrors.membershipUnverifiable();
			case TEACHER -> {
			}
		}
		teachingClass.setTeacherSub(teacherSub);
		teachingClass.setUpdatedAt(OffsetDateTime.now());
		return TeachingClassView.of(this.classes.save(teachingClass));
	}

	@Transactional
	public MemberView enroll(UUID classId, String studentSub) {
		TenantContext context = requireCenterAdmin();
		if (studentSub == null || studentSub.isBlank()) {
			throw OrganizationErrors.validation("studentSub không được để trống");
		}
		this.classes.findById(classId).orElseThrow(OrganizationErrors::notFound);
		// Ghi danh được phép cho cả người chưa phải thành viên (ghi danh với tư cách khách và không tạo bất kỳ
		// tư cách thành viên nào ở Keycloak), nhưng vẫn phải xác minh được tình trạng thành viên hiện hành của
		// đối tượng: không xác minh được thì không ghi danh mù.
		if (this.membershipChecker.check(context.organizationId(), studentSub)
				== MembershipChecker.Result.UNVERIFIABLE) {
			throw OrganizationErrors.membershipUnverifiable();
		}
		Optional<ClassMember> existing = this.members.findByClassIdAndStudentSub(classId, studentSub);
		OffsetDateTime now = OffsetDateTime.now();
		if (existing.isPresent()) {
			ClassMember member = existing.get();
			if (member.getRemovedAt() == null) {
				throw OrganizationErrors.conflict("Học viên đã đang ghi danh vào lớp này");
			}
			member.setRemovedAt(null);
			member.setEnrolledBySub(context.sub());
			member.setUpdatedAt(now);
			return MemberView.of(this.members.save(member));
		}
		ClassMember member = new ClassMember();
		member.setId(UUID.randomUUID());
		member.setClassId(classId);
		member.setStudentSub(studentSub);
		member.setEnrolledBySub(context.sub());
		member.setCreatedAt(now);
		member.setUpdatedAt(now);
		return MemberView.of(this.members.save(member));
	}

	@Transactional
	public void removeMember(UUID classId, String studentSub) {
		requireCenterAdmin();
		this.classes.findById(classId).orElseThrow(OrganizationErrors::notFound);
		ClassMember member = this.members.findByClassIdAndStudentSub(classId, studentSub)
			.filter(found -> found.getRemovedAt() == null)
			.orElseThrow(OrganizationErrors::notFound);
		OffsetDateTime now = OffsetDateTime.now();
		member.setRemovedAt(now);
		member.setUpdatedAt(now);
		this.members.save(member);
	}

	private List<TeachingClass> visibleClassesForMember(TenantContext context) {
		Set<UUID> classIds = new LinkedHashSet<>();
		this.classes.findByTeacherSubOrderByCreatedAtAscIdAsc(context.sub())
			.forEach(teachingClass -> classIds.add(teachingClass.getId()));
		this.members.findByStudentSubAndRemovedAtIsNull(context.sub())
			.forEach(member -> classIds.add(member.getClassId()));
		if (classIds.isEmpty()) {
			return List.of();
		}
		return this.classes.findByIdInOrderByCreatedAtAscIdAsc(classIds);
	}

	private List<TeachingClassView> guestClasses(TenantContext context) {
		UUID organizationId = this.guestScope.pinPartitionForPage(context.organizationAlias());
		return this.jdbc.query("""
				select tc.id, tc.name, tc.course_id, tc.teacher_sub
				from teaching_class tc
				where tc.partition_key = ?
				  and exists (select 1 from class_member cm
				              where cm.class_id = tc.id and cm.partition_key = tc.partition_key
				                and cm.student_sub = ? and cm.removed_at is null)
				order by tc.created_at, tc.id
				""", (resultSet, rowNum) -> new TeachingClassView(resultSet.getObject("id", UUID.class),
				resultSet.getString("name"), resultSet.getObject("course_id", UUID.class),
				resultSet.getString("teacher_sub")), organizationId.toString(), context.sub());
	}

	private List<CourseView> guestCourses(TenantContext context) {
		UUID organizationId = this.guestScope.pinPartitionForPage(context.organizationAlias());
		return this.jdbc.query("""
				select c.id, c.name
				from course c
				where c.partition_key = ?
				  and exists (select 1 from teaching_class tc
				              join class_member cm
				                on cm.class_id = tc.id and cm.partition_key = tc.partition_key
				              where tc.course_id = c.id and tc.partition_key = c.partition_key
				                and cm.student_sub = ? and cm.removed_at is null)
				order by c.created_at, c.id
				""", (resultSet, rowNum) -> new CourseView(resultSet.getObject("id", UUID.class),
				resultSet.getString("name")), organizationId.toString(), context.sub());
	}

	private TeachingClassView guestClassDetail(TenantContext context, UUID classId) {
		UUID organizationId = this.guestScope.pinPartitionForPage(context.organizationAlias());
		List<TeachingClassView> found = this.jdbc.query("""
				select tc.id, tc.name, tc.course_id, tc.teacher_sub
				from teaching_class tc
				where tc.partition_key = ? and tc.id = ?
				  and exists (select 1 from class_member cm
				              where cm.class_id = tc.id and cm.partition_key = tc.partition_key
				                and cm.student_sub = ? and cm.removed_at is null)
				""", (resultSet, rowNum) -> new TeachingClassView(resultSet.getObject("id", UUID.class),
				resultSet.getString("name"), resultSet.getObject("course_id", UUID.class),
				resultSet.getString("teacher_sub")), organizationId.toString(), classId, context.sub());
		if (found.isEmpty()) {
			throw OrganizationErrors.forbidden();
		}
		return found.get(0);
	}

	private TenantContext requireCenterAdmin() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw OrganizationErrors.forbidden();
		}
		if (context.guest() || context.organizationId() == null || !isAdmin(context)) {
			throw OrganizationErrors.forbidden();
		}
		return context;
	}

	private boolean isAdmin(TenantContext context) {
		return context.organizationRoles().contains(ADMIN_ROLE);
	}

	private void requireName(String name) {
		if (name == null || name.isBlank()) {
			throw OrganizationErrors.validation("name không được để trống");
		}
	}

	public record CourseView(UUID id, String name) {

		static CourseView of(Course course) {
			return new CourseView(course.getId(), course.getName());
		}

	}

	public record TeachingClassView(UUID id, String name, UUID courseId, String teacherSub) {

		static TeachingClassView of(TeachingClass teachingClass) {
			return new TeachingClassView(teachingClass.getId(), teachingClass.getName(),
					teachingClass.getCourseId(), teachingClass.getTeacherSub());
		}

	}

	public record MemberView(String studentSub, String enrolledBySub, OffsetDateTime enrolledAt) {

		static MemberView of(ClassMember member) {
			return new MemberView(member.getStudentSub(), member.getEnrolledBySub(), member.getCreatedAt());
		}

	}

}
