import { SURNAMES, studentId } from "./students-seed.ts";

/**
 * The fictitious guardians of the simulated registry (guardians cut). Plain data with fixed identifiers, so a journey can
 * name a row and a test can assert on it. Nothing here is imported by the app or by the build. Thirty guardians, one for each
 * surname of the students seed (two pages of 25 by default), every tenth inactive, a third with a national id and a fifth with
 * an RTN. The full national id and RTN live only in the simulator, apart from the rows: no response carries them. The seed
 * links the first student (Ana Aguilar) to guardian 1 at 60.00 and guardian 2 at 40.00.
 */
export type Channel = "EMAIL" | "SMS" | "WHATSAPP" | "NONE";
export type GuardianStatus = "ACTIVE" | "INACTIVE";
export type Relationship = "FATHER" | "MOTHER" | "LEGAL_GUARDIAN" | "SELF" | "OTHER";

export interface GuardianRow {
  id: string;
  guardianCode: string;
  firstName: string;
  lastName: string;
  email: string | null;
  phone: string | null;
  address: string | null;
  preferredChannel: Channel;
  status: GuardianStatus;
  createdAt: string;
  updatedAt: string;
}

export interface LinkRow {
  linkId: string;
  studentId: string;
  guardianId: string;
  relationship: Relationship;
  /** Two decimals as the server keeps them, as the number it sends. */
  financialResponsibilityPercent: number;
  isPrimaryPayer: boolean;
  isBillingRecipient: boolean;
  validFrom: string;
}

export interface GuardianRows {
  guardians: GuardianRow[];
  /** The full national id and RTN of a guardian that has them, as the simulator keeps them: never in a row, never in a response. */
  documents: Map<string, string>;
  rtns: Map<string, string>;
  /** The links in force now. A replaced link is kept in `history`, as the server does. */
  links: LinkRow[];
  history: LinkRow[];
}

const STAMP = "2026-11-01T12:00:00Z";
const FROM = "2026-03-02";
export const guardianId = (n: number): string => `00000000-0000-4000-8000-${String(9000 + n).padStart(12, "0")}`;
export const guardianCodeOf = (n: number): string => `P-${String(n).padStart(6, "0")}`;
export const linkIdOf = (n: number): string => `00000000-0000-4000-8000-${String(8000 + n).padStart(12, "0")}`;
export const seededGuardianDocument = (n: number): string => `0801-1980-${String(20000 + n)}`;
export const seededGuardianRtn = (n: number): string => `0801198000${String(1000 + n).padStart(4, "0")}`;

const FIRST_NAMES = ["Rosa", "Luis", "Carmen", "José", "Marta", "Pedro", "Elena", "Jorge", "Sonia", "Mario"] as const;

export const emptyGuardians = (): GuardianRows => ({ guardians: [], documents: new Map(), rtns: new Map(), links: [], history: [] });

export function seededGuardians(): GuardianRows {
  const documents = new Map<string, string>();
  const rtns = new Map<string, string>();
  const guardians = SURNAMES.map((lastName, index): GuardianRow => {
    const n = index + 1;
    const row: GuardianRow = {
      id: guardianId(n),
      guardianCode: guardianCodeOf(n),
      firstName: FIRST_NAMES[index % FIRST_NAMES.length] ?? "Rosa",
      lastName,
      email: n % 2 === 0 ? `encargado${String(n)}@example.test` : null,
      phone: n % 4 === 0 ? "+504 9999-0000" : null,
      address: null,
      preferredChannel: n % 2 === 0 ? "EMAIL" : "NONE",
      status: n % 10 === 0 ? "INACTIVE" : "ACTIVE",
      createdAt: STAMP,
      updatedAt: STAMP,
    };
    if (n % 3 === 0) documents.set(row.id, seededGuardianDocument(n));
    if (n % 5 === 0) rtns.set(row.id, seededGuardianRtn(n));
    return row;
  });
  const links: LinkRow[] = [
    {
      linkId: linkIdOf(1),
      studentId: studentId(1),
      guardianId: guardianId(1),
      relationship: "MOTHER",
      financialResponsibilityPercent: 60,
      isPrimaryPayer: true,
      isBillingRecipient: true,
      validFrom: FROM,
    },
    {
      linkId: linkIdOf(2),
      studentId: studentId(1),
      guardianId: guardianId(2),
      relationship: "FATHER",
      financialResponsibilityPercent: 40,
      isPrimaryPayer: false,
      isBillingRecipient: false,
      validFrom: FROM,
    },
  ];
  return { guardians, documents, rtns, links, history: [] };
}
