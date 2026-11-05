// Accepted case, not a violation (BX61): a container, which sits one level above components/, may
// import the data library and the API client.
import { useQuery } from "@tanstack/react-query";
import { client } from "../../shared/api/client.ts";

export const container = [useQuery, client];
