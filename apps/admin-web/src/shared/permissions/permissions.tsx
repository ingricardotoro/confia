import { createContext, useContext, useMemo, type ReactNode } from "react";
import type { Permission } from "./permission-codes.ts";

/** What the screens ask of the permissions of the person. */
export interface Permissions {
  /** Whether the session lists `permission`. A code that is not listed never grants anything. */
  readonly can: (permission: Permission) => boolean;
}

const NONE: ReadonlySet<string> = new Set();

const PermissionsContext = createContext<ReadonlySet<string>>(NONE);

export interface PermissionsProviderProps {
  /** The codes of the current session, as the server sent them. */
  readonly permissions: readonly string[];
  readonly children: ReactNode;
}

/**
 * Hands the permissions of the current session to the screens below the shell. It holds no copy of its own:
 * the shell reads the list from the session query (the one place of server state) and passes it down.
 * Outside a provider nobody can do anything, so a screen mounted without a session hides every action.
 */
export function PermissionsProvider({ permissions, children }: PermissionsProviderProps) {
  const granted = useMemo(() => new Set(permissions), [permissions]);
  return <PermissionsContext value={granted}>{children}</PermissionsContext>;
}

/** The permissions of the current session. The interface only hides with this: the server decides each request. */
export function usePermissions(): Permissions {
  const granted = useContext(PermissionsContext);
  return useMemo(() => ({ can: (permission) => granted.has(permission) }), [granted]);
}
