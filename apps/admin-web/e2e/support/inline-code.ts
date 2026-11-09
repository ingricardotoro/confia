/** What the strict CSP would refuse in a page: an inline script, a style element, a style attribute, an event handler. */
export function findInlineCode(html: string): string[] {
  const findings: string[] = [];
  if (/<script(?![^>]*\ssrc=)[^>]*>/i.test(html)) findings.push("inline <script>");
  if (/<style[\s>]/i.test(html)) findings.push("<style>");
  if (/<[a-z][^>]*\sstyle\s*=/i.test(html)) findings.push("style attribute");
  if (/<[a-z][^>]*\son[a-z]+\s*=/i.test(html)) findings.push("event handler");
  return findings;
}
