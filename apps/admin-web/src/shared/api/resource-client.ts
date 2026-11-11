import { request, type ApiResult, type ResponseSchema } from "./http.ts";

/** What every listing takes: `page` counts from 0, `sort` is `field` or `field,asc|desc`. */
export interface PageParams {
  readonly page: number;
  readonly size: number;
  readonly sort?: string | undefined;
}

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });

/** The query string of a listing: only strings and numbers travel, so a filter that is absent is not sent. */
function listPath(base: string, resource: string, params: object): string {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(params) as [string, unknown][]) {
    if (typeof value === "string" || typeof value === "number") query.set(name, String(value));
  }
  const text = query.toString();
  return `${base}/${resource}${text === "" ? "" : `?${text}`}`;
}

export interface ResourceClient {
  readonly list: <T>(resource: string, schema: ResponseSchema<T>, token: string, params: object) => Promise<ApiResult<T>>;
  readonly create: <T>(resource: string, schema: ResponseSchema<T>, token: string, body: unknown) => Promise<ApiResult<T>>;
  readonly update: <T>(resource: string, schema: ResponseSchema<T>, token: string, id: string, body: unknown) => Promise<ApiResult<T>>;
}

/**
 * The three calls every paged resource under `base` makes: a listing, a create and a replace by id. None sends an
 * `Idempotency-Key`, because none moves money; paging, order and filters travel as query parameters and nothing
 * here, or after it, sorts or filters a page in the browser.
 */
export function resourceClient(base: string): ResourceClient {
  return {
    list: (resource, schema, token, params) => request({ method: "GET", path: listPath(base, resource, params), headers: bearer(token), schema }),
    create: (resource, schema, token, body) => request({ method: "POST", path: `${base}/${resource}`, headers: bearer(token), body, schema }),
    update: (resource, schema, token, id, body) =>
      request({ method: "PUT", path: `${base}/${resource}/${encodeURIComponent(id)}`, headers: bearer(token), body, schema }),
  };
}
