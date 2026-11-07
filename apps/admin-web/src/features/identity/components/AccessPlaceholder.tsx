import { Alert } from "@confia/ui";
import { ScreenLayout } from "./ScreenLayout.tsx";

export interface AccessPlaceholderProps {
  readonly title: string;
  /** Why the person arrived here, when it was not by choice. Already translated. */
  readonly notice?: string | undefined;
}

/**
 * The provisional screen of sign in, verification and enrollment. It has a heading and the arrival notice,
 * and no field and no operation on credentials: `admin-web-staff-sign-in` replaces it.
 */
export function AccessPlaceholder({ title, notice }: AccessPlaceholderProps) {
  return <ScreenLayout title={title}>{notice === undefined ? null : <Alert variant="info">{notice}</Alert>}</ScreenLayout>;
}
