// Deliberate violation of ui-never-fetches-data: presentation importing a data library.
import { useQuery } from "@tanstack/react-query";

export const leaked = useQuery;
