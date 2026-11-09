import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { classifyAccessFailure, type AccessFailure } from "../../../shared/forms/access-failure.ts";
import { useAccessForm } from "../../../shared/forms/use-access-form.ts";
import type { AccessSession } from "../session/index.ts";
import { SecondFactorForm } from "./components/SecondFactorForm.tsx";
import {
  normalizeCode,
  normalizeRecoveryCode,
  SECOND_FACTOR_CONTROL_IDS,
  validateSecondFactor,
  type SecondFactorField,
  type SecondFactorMode,
} from "./second-factor-schema.ts";

export interface SecondFactorContainerProps {
  readonly session: AccessSession;
  /** Where each end of the screen leads. Props, so this file does not import the routes. */
  readonly homePath: string;
  readonly signInPath: string;
}

interface EntryProps extends SecondFactorContainerProps {
  readonly mode: SecondFactorMode;
  readonly focusField: boolean;
  readonly onToggleMode: () => void;
}

/**
 * The form of one mode. It is mounted with `key={mode}` by the container, so a change of mode starts it over: values and
 * errors of the other mode are gone and the request can only carry the field in view (design.md E9, SS65). It declares both
 * fields only so that the hook has a normalization for each; `validate` and `submit` look at the one of the mode.
 */
function SecondFactorEntry({ session, homePath, signInPath, mode, focusField, onToggleMode }: EntryProps) {
  const navigate = useNavigate();

  async function backToSignIn(): Promise<undefined> {
    // The restricted token ended or is gone: the sign in starts over and tells the person why (SS28, SS69, SS70).
    session.discardSecondFactor("second-factor-interrupted");
    await navigate({ to: signInPath, replace: true });
    return undefined;
  }

  const form = useAccessForm<SecondFactorField>({
    fields: {
      code: { controlId: SECOND_FACTOR_CONTROL_IDS.code, normalize: normalizeCode },
      recoveryCode: { controlId: SECOND_FACTOR_CONTROL_IDS.recoveryCode, normalize: normalizeRecoveryCode },
    },
    validate: (values) => validateSecondFactor(mode, values),
    waitingKey: "secondFactor.waiting",
    async submit(values): Promise<AccessFailure | undefined> {
      const outcome = await session.verify(
        mode === "code" ? { kind: "code", value: values.code } : { kind: "recovery-code", value: values.recoveryCode },
      );
      if (outcome.kind === "authenticated") {
        await navigate({ to: homePath, replace: true });
        return undefined;
      }
      if (outcome.kind === "no-token") return backToSignIn();

      const failure = classifyAccessFailure(outcome.result);
      if (failure.kind === "restricted-token-ended") return backToSignIn();
      // The text of a code that is not accepted depends on the mode, and neither says why (SS67, SS68).
      if (failure.kind === "message" && outcome.result.kind === "problem" && outcome.result.problem.code === "second-factor-invalid") {
        return { ...failure, key: mode === "code" ? "problem.secondFactorInvalid" : "problem.recoveryInvalid" };
      }
      return failure;
    },
  });

  return (
    <SecondFactorForm
      {...form}
      mode={mode}
      focusField={focusField}
      onToggleMode={onToggleMode}
      onBack={() => {
        // The guard of the root route discards the token when the screen is left, with no notice (SS73).
        void navigate({ to: signInPath });
      }}
    />
  );
}

/**
 * The verification screen (design.md E1, E5, E9). It keeps the mode and sends one proof through the session; the
 * shared hook does the rest. A code never leaves the form: no query or mutation holds it, so none reaches a cache (SS03).
 */
export function SecondFactorContainer({ session, homePath, signInPath }: SecondFactorContainerProps) {
  const [mode, setMode] = useState<SecondFactorMode>("code");
  // The focus goes to the new field only when the person changed the mode; on arrival the heading keeps it (SS32, SS64).
  const [changedByPerson, setChangedByPerson] = useState(false);

  return (
    <SecondFactorEntry
      key={mode}
      session={session}
      homePath={homePath}
      signInPath={signInPath}
      mode={mode}
      focusField={changedByPerson}
      onToggleMode={() => {
        setChangedByPerson(true);
        setMode((current) => (current === "code" ? "recovery-code" : "code"));
      }}
    />
  );
}
