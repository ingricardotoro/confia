import type {
  AccrualPreviewResponseOutput,
  AccrualRunItemResponseOutput,
  AccrualRunResponseOutput,
  ChargePlanResponseOutput,
  StudentStatementLineOutput,
  StudentStatementOutput,
  StudentStatementSectionOutput,
} from "@confia/contracts/admin";

// Types the presentation components of this feature read. They come from the generated contracts, not from shared/api: a
// presentation component never imports the API client module, not even for a type (admin-presentation-is-pure).
export type AccrualPreview = AccrualPreviewResponseOutput;
export type AccrualRun = AccrualRunResponseOutput;
export type AccrualRunItem = AccrualRunItemResponseOutput;
export type AccrualRunStatus = AccrualRunResponseOutput["status"];
export type AccrualOutcome = AccrualRunItemResponseOutput["outcome"];
export type ChargePlanStatus = ChargePlanResponseOutput["status"];
export type StudentStatementResponse = StudentStatementOutput;
export type StatementSection = StudentStatementSectionOutput;
export type StatementLine = StudentStatementLineOutput;
