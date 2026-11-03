export const queryKeys = {
  contentBankSpaces: ['content-bank', 'spaces'] as const,
  contentBankItems: ['content-bank', 'items'] as const,
  contentBankItem: (itemId: string) => ['content-bank', 'items', itemId] as const,
  contentBankItemFileUrl: (itemId: string) => ['content-bank', 'items', itemId, 'file-url'] as const,
  contentBankSubjects: ['content-bank', 'subjects'] as const,
  contentBankQuestionCategories: ['content-bank', 'question-categories'] as const,
  contentBankCurriculumNodes: (subjectId: string, grade: number) =>
    ['content-bank', 'curriculum-nodes', subjectId, grade] as const,
};
