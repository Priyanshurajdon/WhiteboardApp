# 📊 Whiteboard PPT – Android App

A full-featured Android whiteboard app that lets you import PowerPoint presentations,
annotate slides with rich drawing tools, and export the annotated PPTX back out.

---

## ✨ Features

### Slide Management
- **Import PPTX** – Opens system file picker, supports `.pptx` files
- **Previous / Next** – Navigate slides with slide counter display
- **Export PPTX** – Bakes your drawings into the slides and saves/shares the file

### Drawing Tools
| Tool       | Description                              |
|------------|------------------------------------------|
| ✏️ Pen      | Smooth freehand drawing                  |
| 🖍 Highlighter | Semi-transparent wide strokes           |
| ⬜ Eraser   | Clear portions of your drawings          |
| ➖ Line     | Straight lines between two points        |
| ▭ Rectangle | Drag to draw a rectangle outline        |
| ⭕ Circle   | Drag to draw an oval/circle outline      |

### Controls
- **3 Stroke Sizes** – S / M / L
- **8 Color Swatches** – Red, Blue, Teal, Yellow, Orange, Purple, White, Black
- **Undo** – Step back one stroke at a time
- **Clear** – Remove all drawings on the current slide
- **Per-slide Layers** – Each slide keeps its own annotation layer

---

## 🛠 Setup Instructions

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9.x
- Minimum device: Android 7.0 (API 24)

### Steps

1. **Open in Android Studio**
   ```
   File → Open → select the `WhiteboardApp` folder
   ```

2. **Sync Gradle**
   Android Studio will prompt you — click "Sync Now".
   This downloads Apache POI (~15 MB) and other dependencies.

3. **Build & Run**
   - Connect a device or start an emulator
   - Press ▶️ Run
   - The app forces **landscape orientation** (best for presentations)

### APK Build
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📐 Architecture

```
MainActivity.kt         – UI controller, wires all buttons/callbacks
DrawingView.kt          – Custom View; handles all touch drawing, per-slide layers, undo
PptxManager.kt          – Apache POI wrapper: load, render, export PPTX
AndroidGraphics2D.kt    – Bridges Java2D drawing calls (used by POI) → Android Canvas
```

### How PPTX Rendering Works

Apache POI renders slides using Java2D (`Graphics2D`). On Android, Java2D is not
available, so `AndroidGraphics2D` intercepts every draw call and forwards it to
an Android `Canvas`. This handles:
- Shapes, fills, strokes
- Text with fonts
- Images embedded in slides
- Paths and curves

### How Export Works

1. For each annotated slide, the `DrawingView` returns a transparent `Bitmap`
2. `PptxManager` scales each annotation bitmap to the slide's native size
3. Each annotation is saved as a PNG and added as a picture shape on top of the slide
4. The modified presentation is saved as a new `.pptx` file

---

## 🔧 Customization

### Add more colors
In `activity_main.xml`, add more `<View>` color swatches in the `colorPalette` LinearLayout,
then register them in `MainActivity.setupToolbar()` with `setupColorSwatch(view, colorInt)`.

### Change default stroke
In `setupDrawingToolbar()`, change `setStrokeSize(6f, ...)` to your preferred default.

### Add text tool
Extend `DrawingView.Tool` with `TEXT`, handle it in `onTouchEvent` to show a dialog,
then render with `canvas.drawText(...)` in `drawActions()`.

---

## ⚠️ Known Limitations

1. **Complex slides** with heavy animations or media may not render pixel-perfect —
   Apache POI's Java2D rendering has some limitations.
2. **Large PPTX files** (>50 slides or high-res images) may be slow to load on
   lower-end devices due to in-memory bitmap rendering.
3. The `AndroidGraphics2D` bridge covers the most common drawing operations; exotic
   gradient fills or advanced compositing may fall back to solid colors.

---

## 📦 Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Apache POI | 5.2.5 | PPTX read/write |
| Material Components | 1.11.0 | UI components |
| AndroidX ConstraintLayout | 2.1.4 | Layouts |
| Kotlin Coroutines | built-in | Background processing |

---

## 📄 License
MIT – use freely in personal and commercial projects.
