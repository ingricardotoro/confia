import { describe, expect, it } from "vitest";
import { referenceNamesOf, sectionNamesOf, sectionOptionsOf } from "./charge-plan-references.ts";

const label = ({ grade, section, year }: { grade: string; section: string; year: string }) => `${grade} ${section} (${year})`;
const GRADES = [{ id: "g1", name: "Primero" }];
const YEARS = [{ id: "y1", name: "Año 2026" }];
const SECTIONS = [
  { id: "s1", academicYearId: "y1", gradeId: "g1", name: "A" },
  { id: "s2", academicYearId: "y9", gradeId: "g9", name: "B" },
];

describe("the names of the targets of a plan", () => {
  it("writes a section with its grade, its name and its year, and a missing grade or year as not available", () => {
    const names = sectionNamesOf(SECTIONS, GRADES, YEARS, label, "No disponible");

    expect(names.get("s1")).toBe("Primero A (Año 2026)");
    expect(names.get("s2")).toBe("No disponible B (No disponible)");
  });

  it("joins the modalities, the grades and the sections by id", () => {
    const names = referenceNamesOf([{ id: "m1", name: "Primaria" }], GRADES, sectionNamesOf(SECTIONS, GRADES, YEARS, label, "-"));

    expect([...names]).toEqual([
      ["m1", "Primaria"],
      ["g1", "Primero"],
      ["s1", "Primero A (Año 2026)"],
      ["s2", "- B (-)"],
    ]);
  });

  it("offers the sections in the order they came, by their written name", () => {
    const names = sectionNamesOf(SECTIONS, GRADES, YEARS, label, "-");

    expect(sectionOptionsOf(SECTIONS, names)).toEqual([
      { value: "s1", label: "Primero A (Año 2026)" },
      { value: "s2", label: "- B (-)" },
    ]);
  });
});
