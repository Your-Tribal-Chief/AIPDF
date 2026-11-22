# AllPDF Pro — offline all-in-one PDF suite for Android

35 PDF tools that run entirely on the phone's CPU. The APK has **no INTERNET permission**.

**Download:** see [Releases](../../releases) (built and signed by GitHub Actions on every push).
**Requires:** Android 8.0+ (API 26), targets Android 15 (API 35); tested on an Android 14 emulator in CI.

## Tools
| Category | Tools |
|---|---|
| Organize | Merge · Split (ranges / interval / burst) · Remove pages · Extract pages (PDF or ZIP) · Organize (drag reorder, rotate, blank pages, insert PDF, delete) · Scan to PDF (camera, auto/manual crop, rotate, Original/Gray/B&W) |
| Optimize | Compress (Extreme / Recommended / Less, before-after size) · Repair (xref rebuild → salvage → re-render) · OCR (searchable PDF with invisible text layer + .txt) |
| Convert to PDF | JPG/PNG/WebP · Word (DOCX, DOC, RTF, TXT) · PowerPoint (PPTX, PPT) · Excel (XLSX, XLS, CSV) · HTML (file or pasted) |
| Convert from PDF | JPG/PNG (render at DPI or extract originals) · Word · PowerPoint (exact look or editable) · Excel (table detection) · PDF/A-2B (preserve or strict) · Markdown |
| Edit | Resize (A3–Legal, custom mm/in/px; Fit/Fill/Center) · Crop (visual) · Rotate (batch) · Page numbers · Watermark (text/image, 9-grid or tiled, over/under) · Edit (text, images, shapes, arrows, highlight, freehand) · Forms (fill AcroForms, add text fields/checkboxes, flatten) |
| Security | Protect (AES-256, permissions) · Unlock · Sign (draw / type / import, place & resize) · Redact (pages flattened to pixels — text destroyed) · Compare (word-level text diff + visual overlay report) |
| Productivity | Workflows (chain steps, save presets) · Summarizer (TF-IDF + TextRank, on-device) · Viewer & files (pinch zoom, night mode, recents, output history, favorites, share) |

## Libraries
PdfBox-Android (Apache 2.0), Apache POI 3.17 (legacy Office), ML Kit Text Recognition with the bundled Latin model (on-device). DOCX/PPTX/XLSX are read and written by the app's own OOXML code.

## Signing
CI signs with `signing/allpdf-ci.jks` (a public, convenience key — fine for sideloading).
For a private release key, add repository secrets `ALLPDF_KEYSTORE_BASE64`, `ALLPDF_KEYSTORE_PASSWORD`, `ALLPDF_KEY_ALIAS`, `ALLPDF_KEY_PASSWORD`.

## CI
`.github/workflows/build-apk.yml` builds and verifies the release APK (apksigner, permissions), then runs 32 on-device engine tests plus a UI smoke test on an Android 14 emulator.
