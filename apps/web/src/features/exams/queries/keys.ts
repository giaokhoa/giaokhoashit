export const queryKeys = {
  examPaperDetail: (paperId: string) => ['exam-papers', paperId] as const,
  examPaperPins: (paperId: string) => ['exam-papers', paperId, 'pins'] as const,
};
