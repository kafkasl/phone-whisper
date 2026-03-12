# Phone Whisper - Project Guidelines & Style

This file contains design and architectural rules for the Phone Whisper app. Follow these constraints when building or modifying the UI.

## UI & Design Constraints

### 1. Programmatic UI (No XML Layouts)
- Do not use XML layout files for activities or fragments.
- Build the UI programmatically in Kotlin (e.g., building `LinearLayout`, `TextView`, etc. directly in code).

### 2. Settings Row Paradigm
- The app must exactly match the native Android Settings app aesthetic.
- Use flat, full-width, clickable rows.
- **NO** elevated cards, shadows, or collapsible accordions.
- Each row should typically have:
  - **Primary Title**: 18sp
  - **Muted Subtitle**: 14sp (`textColorSecondary`)

### 3. Strict, Neutral Color Palette
- **Google Blue (`#1A73E8`)** is the primary accent color (`colorPrimary`).
- This overrides the default Material 3 dusty purple fallback to ensure a professional, tech-forward look.
- Use `Theme.PhoneWhisper` to unify colors across Day/Night themes.

### 4. Zero-Emoji Policy
- Do NOT use emojis (e.g., ⭐, ✅, ❌, ⚠️) anywhere in the app UI.
- Rely strictly on semantic text colors (e.g., `colorPrimary` for active/selected, `textColorSecondary` for muted/unavailable) and clean typography/spacing to establish visual hierarchy and status.

### 5. API Key Masking
- API keys must be masked in the settings row subtitle (e.g., `sk-...WXYZ` or `sk-...***`).
- Never display the full key in clear text in the main view.

### 6. Edge-to-Edge Integration
- Avoid the app feeling "boxed in."
- Set `android:statusBarColor` and `android:navigationBarColor` to match the background color (`?android:attr/colorBackground`).

### 7. Toggles & Visibility
- Use `MaterialSwitch` for toggles (e.g., "Use cloud transcription").
- Avoid inline radio buttons where a switch makes more sense.
- Toggling modes should dynamically show/hide relevant sections (e.g., enabling cloud mode automatically hides the entire "Local models" section to prevent visual confusion).
