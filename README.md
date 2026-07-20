# PDF Text Editor (Android MVP)

A high-fidelity, in-place PDF Text Editor built in Kotlin using Jetpack Compose, Clean Architecture + MVVM, Hilt dependency injection, and the **Apryse (PDFTron) Android SDK**.

## Features
1. **Open PDF**: Select PDF files from local storage using the Storage Access Framework (SAF).
2. **Text Block Extraction**: Uses `TextExtractor` to parse text runs, bounding boxes, fonts, sizes, and colors.
3. **In-place Editing**: Select any text run, enter new text, and swap content streams dynamically preserving position, font size, alignment, and color.
4. **Undo/Redo**: Maintain full in-memory edit operations prior to document export.
5. **Export PDF**: Save the modified PDF under a user-defined filename using SAF without overwriting the original.

---

## Architectural Overview

The application follows the principles of Clean Architecture combined with MVVM presentation layer structure:

```
  ┌─────────────────────────────────────────────────────────────┐
  │                      presentation                           │
  │   [HomeScreen]  ──►  [PdfViewerScreen]                      │
  │                            │                                │
  │                            ▼                                │
  │                   [PdfViewerViewModel]                      │
  └────────────────────────────┬────────────────────────────────┘
                               │ (StateFlow / User Actions)
                               ▼
  ┌─────────────────────────────────────────────────────────────┐
  │                         domain                              │
  │     [UseCases]  ──►  [PdfRepository] (Interface)            │
  │                            │                                │
  └────────────────────────────┼────────────────────────────────┘
                               │ (Dependency Inversion)
                               ▼
  ┌─────────────────────────────────────────────────────────────┐
  │                          data                               │
  │    [PdfRepositoryImpl]  ──►  [PdfEngine] (Interface)        │
  │                                  │                          │
  │                                  ▼                          │
  │                           [ApryseEngine]                    │
  │                                  │                          │
  │                                  ▼                          │
  │                         (Apryse SDK v10.4)                  │
  └─────────────────────────────────────────────────────────────┘
```

---

## SDK License Key Setup

To evaluate the editing capabilities, the app automatically boots in **Demo / Trial mode** by default if no license key is provided.

For commercial use, obtain a key from [Apryse PDFTron Portal](https://apryse.com/) and register it:

1. Open the local config file `local.properties` in your project root.
2. Define your key as follows:
   ```properties
   pdftron.license.key=YOUR_COMMERCIAL_LICENSE_KEY_HERE
   ```
3. During build time, the Gradle buildscript reads this property and embeds it as a `BuildConfig` variable which is loaded during application initialization in `EditPdfApplication.kt`.

---

## Known Limitations

- **Font Substitution**: If the modified text contains glyphs or fonts that are not embedded in the original PDF document, the viewer falls back to standard system fonts (Helvetica/Arial, Courier, etc.). This might slightly alter the look of edited fields.
- **OCR/Scanned Documents**: The current version only supports modifying digital PDF documents containing native text content streams. Purely scanned images or flattened documents cannot be edited without an OCR pre-processing layer.
- **Complex Reflow**: Replacing a short word with a very long sentence can overlap adjacent text blocks because this MVP acts on direct content stream regions. Use within reasonable text bounds.

---

## Build & Run

1. Open the project in Android Studio (Jellyfish or later).
2. Sync the project with Gradle Files.
3. Build the application and run on an Android device or emulator running **API 26 (Android 8.0) or higher**.
