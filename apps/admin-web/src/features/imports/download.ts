/**
 * Offers a text as a file to save. The text is built by the screen from its own words (the template, the lines of the
 * rejections); the object URL lives for the click only, so nothing stays in the browser.
 */
export function downloadText(fileName: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.rel = "noopener";
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
