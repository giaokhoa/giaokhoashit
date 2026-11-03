import { Body, Controller, Delete, Get, Param, Patch, Post, Query } from '@nestjs/common';
import { uuidIdSchema } from '@lms/contracts';
import { ApiTags } from '@nestjs/swagger';
import { CurrentUser, type AuthenticatedUser } from '../../common/current-user.decorator';
import { CurriculumService } from './curriculum.service';
import { ProgramImportService } from './program-import.service';
import {
  createCurriculumNodeSchema, createCurriculumSchema, createLearningOutcomeSchema, createSubjectSchema,
  importProgramDatasetSchema,
  updateCurriculumNodeSchema, updateCurriculumSchema, updateLearningOutcomeSchema, updateSubjectSchema,
  type CreateCurriculumDto, type CreateCurriculumNodeDto, type CreateLearningOutcomeDto, type CreateSubjectDto,
  type ImportProgramDatasetDto,
  type UpdateCurriculumDto, type UpdateCurriculumNodeDto, type UpdateLearningOutcomeDto, type UpdateSubjectDto,
} from './dto/curriculum.dto';

@ApiTags('curriculum')
@Controller({ path: 'curriculum', version: '1' })
/** API chương trình (curriculum GDPT 2018, port từ giaokhoa): subjects → curricula → cây nodes → learning outcomes. Đọc
 * cần curriculum:view, ghi cần curriculum:manage tại context của môn học (qua PermissionEvaluator, trong service).
 * Riêng các GET phục vụ chọn khung (subjects/curricula/tree) nhận thêm `forCourseId`: người có course:update tại khoá
 * đó cũng đọc được — nhánh cho giáo viên cấp khoá nạp khung vào khoá mình dạy (issue #66).
 */
export class CurriculumController {
  constructor(
    private readonly curriculum: CurriculumService,
    private readonly programImport: ProgramImportService,
  ) {}

  @Get('program-import')
  async listProgramImportStatus(@CurrentUser() actor: AuthenticatedUser) {
    return this.programImport.listDatasetStatus(actor.id);
  }
  @Post('program-import')
  /** POST /curriculum/program-import — nạp/nạp lại một dataset vào cây chương trình (idempotent theo sổ đăng ký). */
  async importProgramDataset(@Body({ schema: importProgramDatasetSchema }) dto: ImportProgramDatasetDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.programImport.importDataset(actor.id, dto.datasetKey);
  }

  @Post('subjects')
  async createSubject(@Body({ schema: createSubjectSchema }) dto: CreateSubjectDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.createSubject(actor.id, dto);
  }
  @Get('subjects')
  /** GET /curriculum/subjects?contextId=&forCourseId= — môn học trong một context (forCourseId: nhánh chọn khung cho
   * khoá). `contextId` là tùy chọn: vắng thì service mặc định về context hệ thống qua `contextOrSystem` (lược đồ tùy
   * chọn vẫn bắt giá trị đã kèm phải là UUID, nên id sai định dạng vẫn 400 như cũ). Cổng đọc không đổi: vẫn
   * `assertCanRead` trong service, không nới quyền.
   */
  async listSubjects(@Query('contextId', { schema: uuidIdSchema.optional() }) contextId: string | undefined, @Query('forCourseId') forCourseId: string | undefined, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.listSubjects(actor.id, contextId, forCourseId);
  }
  @Get('subjects/:id')
  async getSubject(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.getSubject(actor.id, id);
  }
  @Patch('subjects/:id')
  async updateSubject(@Param('id', { schema: uuidIdSchema }) id: string, @Body({ schema: updateSubjectSchema }) dto: UpdateSubjectDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.updateSubject(actor.id, id, dto);
  }
  @Delete('subjects/:id')
  /** DELETE /curriculum/subjects/:id — xoá môn học (cascade toàn bộ cây bên dưới). */
  async removeSubject(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    await this.curriculum.deleteSubject(actor.id, id);
    return { deleted: true };
  }

  @Post('curricula')
  async createCurriculum(@Body({ schema: createCurriculumSchema }) dto: CreateCurriculumDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.createCurriculum(actor.id, dto);
  }
  @Get('curricula')
  async listCurricula(@Query('subjectId', { schema: uuidIdSchema }) subjectId: string, @Query('forCourseId') forCourseId: string | undefined, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.listCurricula(actor.id, subjectId, forCourseId);
  }
  @Get('curricula/:id')
  async getCurriculum(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.getCurriculum(actor.id, id);
  }
  @Get('curricula/:id/tree')
  async getTree(@Param('id', { schema: uuidIdSchema }) id: string, @Query('forCourseId') forCourseId: string | undefined, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.getTree(actor.id, id, forCourseId);
  }
  @Patch('curricula/:id')
  async updateCurriculum(@Param('id', { schema: uuidIdSchema }) id: string, @Body({ schema: updateCurriculumSchema }) dto: UpdateCurriculumDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.updateCurriculum(actor.id, id, dto);
  }
  @Delete('curricula/:id')
  /** DELETE /curriculum/curricula/:id — xoá chương trình (cascade cây node + outcomes). */
  async removeCurriculum(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    await this.curriculum.deleteCurriculum(actor.id, id);
    return { deleted: true };
  }

  @Post('nodes')
  async createNode(@Body({ schema: createCurriculumNodeSchema }) dto: CreateCurriculumNodeDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.createNode(actor.id, dto);
  }
  @Get('nodes/:id')
  async getNode(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.getNode(actor.id, id);
  }
  @Patch('nodes/:id')
  async updateNode(@Param('id', { schema: uuidIdSchema }) id: string, @Body({ schema: updateCurriculumNodeSchema }) dto: UpdateCurriculumNodeDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.updateNode(actor.id, id, dto);
  }
  @Delete('nodes/:id')
  /** DELETE /curriculum/nodes/:id — xoá node (cascade node con + outcomes). */
  async removeNode(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    await this.curriculum.deleteNode(actor.id, id);
    return { deleted: true };
  }

  @Post('outcomes')
  async createOutcome(@Body({ schema: createLearningOutcomeSchema }) dto: CreateLearningOutcomeDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.createOutcome(actor.id, dto);
  }
  @Get('outcomes')
  async listOutcomes(@Query('nodeId', { schema: uuidIdSchema }) nodeId: string, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.listOutcomes(actor.id, nodeId);
  }
  @Get('outcomes/:id')
  async getOutcome(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.getOutcome(actor.id, id);
  }
  @Get('outcomes/:id/question-counts')
  /** GET /curriculum/outcomes/:id/question-counts — số câu ready đã gắn theo (mức × dạng), nền đếm ứng viên ma trận (A.6). */
  async getOutcomeQuestionCounts(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.getOutcomeQuestionCounts(actor.id, id);
  }
  @Patch('outcomes/:id')
  async updateOutcome(@Param('id', { schema: uuidIdSchema }) id: string, @Body({ schema: updateLearningOutcomeSchema }) dto: UpdateLearningOutcomeDto, @CurrentUser() actor: AuthenticatedUser) {
    return this.curriculum.updateOutcome(actor.id, id, dto);
  }
  @Delete('outcomes/:id')
  async removeOutcome(@Param('id', { schema: uuidIdSchema }) id: string, @CurrentUser() actor: AuthenticatedUser) {
    await this.curriculum.deleteOutcome(actor.id, id);
    return { deleted: true };
  }
}
