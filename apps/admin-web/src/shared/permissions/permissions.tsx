import { createContext, useContext, useMemo, type ReactNode } from "react";
import type { Permission } from "./permission-codes.ts";

/** What the screens ask of the permissions of the person. */
export interface Permissions {
  /** Whether the session lists `permission`. A code that is not listed never grants anything. */
  readonly can: (permission: Permission) => boolean;
  /**
   * The account of the current session, when the session says it. A screen uses it only to hide an action the person would be refused (deciding what they
   * created themselves); it never grants anything, and the server decides every request.
   */
  readonly accountId: string | undefined;
}

const NONE: ReadonlySet<string> = new Set();

interface Granted {
  readonly permissions: ReadonlySet<string>;
  readonly accountId: string | undefined;
}

const PermissionsContext = createContext<Granted>({ permissions: NONE, accountId: undefined });

export interface PermissionsProviderProps {
  /** The codes of the current session, as the server sent them. */
  readonly permissions: readonly string[];
  /** The account of the current session, from the same query. */
  readonly accountId?: string | undefined;
  readonly children: ReactNode;
}

/**
 * Hands the permissions of the current session to the screens below the shell. It holds no copy of its own:
 * the shell reads the list from the session query (the one place of server state) and passes it down.
 * Outside a provider nobody can do anything, so a screen mounted without a session hides every action.
 */
export function PermissionsProvider({ permissions, accountId, children }: PermissionsProviderProps) {
  const granted = useMemo(() => ({ permissions: new Set(permissions), accountId }), [permissions, accountId]);
  return <PermissionsContext value={granted}>{children}</PermissionsContext>;
}

/** The permissions of the current session. The interface only hides with this: the server decides each request. */
export function usePermissions(): Permissions {
  const granted = useContext(PermissionsContext);
  return useMemo(() => ({ can: (permission) => granted.permissions.has(permission), accountId: granted.accountId }), [granted]);
}
