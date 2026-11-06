// Deliberate violation of admin-presentation-is-pure (BX58): presentation importing TanStack Query.
import { useQuery } from "@tanstack/react-query";

export const leaked = useQuery;
