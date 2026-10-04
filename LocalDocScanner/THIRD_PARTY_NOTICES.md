# Third-party source and dependency notices

LocalDocScanner's own code is MIT. This does not relicense upstream files, OCR
weights, fonts or dependencies. Preserve their notices when redistributing.

| Component | Pinned source / dependency | License and local notice | Use |
|---|---|---|---|
| OpenCV | `org.opencv:opencv:4.12.0`, [opencv/opencv 4.12.0](https://github.com/opencv/opencv/tree/4.12.0) | Apache-2.0, `app/src/main/assets/third_party/opencv_Apache-2.0.txt` | Native contours, perspective transform, morphology, LAB processing; reused binary shared with OCR |
| ZynkSoftware scanner | [f6e20370](https://github.com/zynkware/Document-Scanning-Android-SDK/tree/f6e20370deb5ad30aa5742cf8e9f4838926067d3), `DocumentScanner/src/main/java/com/zynksoftware/documentscanner/common/utils/PerspectiveTransformation.kt` and `OpenCvNativeBridge.kt` | MIT **file headers**, Copyright 2020 ZynkSoftware SRL; `ZynkSoftware_MIT.txt` | Adapted perspective and contour pipeline in `OpenCvDocument.kt`; no TinyOpenCV or upstream UI bundled |
| flatpage | [6e86a5f8](https://github.com/chaxus/flatpage/tree/6e86a5f834f3bd8d03466e3b4f39848d560dc003), `tools/dewarp.py` `_estimate_background`, `flatten_illumination` | MIT, Copyright 2026 chaxus; `flatpage_MIT.txt` | LAB background closing/division port; target background adjusted to 235; not a claim of full curve dewarping |
| Leptonica | [8fdef8f5](https://github.com/DanBloomberg/leptonica/tree/8fdef8f58ea3747ea3ae6525d03c3568a9f3fdf6), `src/pix3.c`, `dewarp2.c`, `dewarp3.c`, `dewarp4.c` | BSD-2-Clause; original license and headers retained under `scan-native/third_party/leptonica` | Native text-line page dewarping with model validation and unchanged fallback; image codecs and utility programs disabled |
| PaddleOCR | [dab3fe35](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf), `deploy/ppocr-android/ppocr-sdk/` | Apache-2.0; `ppocr-sdk/LICENSE-PaddleOCR.txt`; original per-file headers retained | Vendored SDK, local adaptations; PP-OCRv6 tiny/medium ONNX assets |
| PP-OCRv6 ONNX weights | Official PaddlePaddle tiny/medium detection and recognition repositories; exact revisions in `app/src/main/assets/third_party/ocr_models_sources.json` | Apache-2.0 on each official model card; binary hashes in `binary-assets.json` | Offline OCR; the SDK license alone is not used to infer the model license |
| ONNX Runtime | `com.microsoft.onnxruntime:onnxruntime-android:1.21.1` | MIT, `onnxruntime_MIT.txt`, dependency notices | Local OCR inference |
| PdfBox-Android | `com.tom-roush:pdfbox-android:2.0.27.0`; inspected [acf64258](https://github.com/TomRoush/PdfBox-Android/tree/acf64258dd2ce575fea8ac4e51f57cca7f4945d7) | Apache-2.0; `pdfbox_Apache-2.0.txt`, `pdfbox_NOTICE.txt` | Searchable PDF, true annotations, AcroForm appearance generation, merge/split/encryption; dependency APIs used, not copied wholesale |
| LXGW WenKai | `app/src/main/assets/fonts/LXGWWenKai-Regular.ttf` | SIL OFL-1.1; `lxgw-wenkai_OFL.txt` | CJK PDF fonts; original font name and license preserved |
| Liberation Sans 2.1.5 | `app/src/test/resources/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf` | SIL OFL-1.1; `liberation-fonts_OFL.txt`; name table confirms version/license | JVM PDFBox test fallback font, not an app runtime asset |
| Google Material Symbols | `app/src/main/res/drawable/ic_tool_*.xml` | Apache-2.0; `google_material_symbols_LICENSE.txt` | Official vector icons adapted to Android vector drawables |
| ZXing | `com.google.zxing:core:3.5.3` | Apache-2.0 | Barcode / QR reading |
| AndroidX, Compose, CameraX, Room | Versions in Gradle files | Apache-2.0 | UI, local storage, camera, SAF |
| Coil | `io.coil-kt:coil-compose:2.7.0` | Apache-2.0 | Image loading |
| Kotlin and kotlinx.coroutines | Versions in Gradle files | Apache-2.0 | Runtime / concurrency |

GPL/AGPL repositories inspected for comparison are kept outside this app and are
not copied or included in its distribution. ScanTailor's page splitting is a
reference only. `mzucker/page_dewarp` (MIT) remains a researched alternative.

## Embedded Office 26.04.3.1

`office-engine` compiles Java and Android resources from the matching official
[20a46c332c38 source](https://github.com/CollaboraOnline/online.mirror/tree/20a46c332c380925803a1fe538a545c6f9b8fce7).
`LOActivity.java` is modified under MPL-2.0; original headers and `office-engine/COPYING`
remain. Native libraries and engine/browser data come from the verified official
ARM64 runtime listed in `office-engine/runtime-manifest.json` (all paths and hashes).
No upstream APK dex, Android app shell, manifest, certificate, or private key is copied.
See `office-engine/README.md` for exact modifications and source recovery.

The upstream combined license and NOTICE are distributed unchanged at
`assets/license.html` and `assets/notice.txt`, accessible in Settings → Office许可与源码.
They include the licenses/attribution for the native engine, NSS/NSPR, LLVM C++ runtime,
browser components and fonts. Cairo/Hunspell components offering MPL are used under
that option; merely mentioning GPL in a multi-license notice does not select GPL.
Independent spell dictionaries/extensions are excluded from the curated runtime
because their exact individual license inventory has not been established.
The main MIT license does not replace any MPL/other component obligations.


## V4.4 additional libraries

- Gson 2.10.1, Copyright Google Inc.; Apache License 2.0. Source: https://github.com/google/gson/tree/gson-parent-2.10.1
- Fastexcel 0.18.4, Copyright 2016 Dhatim; Apache License 2.0. Source: https://github.com/dhatim/fastexcel/tree/0.18.4
- Opczip 1.2.0, Krzysztof Rzymkowski; Apache License 2.0 (verified Maven POM). Source: https://github.com/rzymek/opczip
- Full license: https://www.apache.org/licenses/LICENSE-2.0

## V4.4.1 additions

- Apache Commons CSV 1.10.0 (`org.apache.commons:commons-csv`), Apache-2.0.
  CSVParser/CSVPrinter directly handle quoting and multiline cells; license and
  upstream NOTICE are bundled as `commons-csv_Apache-2.0.txt` / `commons-csv_NOTICE.txt`.
- Markwon core 4.6.2, Dimitry Ivanov, Apache-2.0; `Markwon_Apache-2.0.txt`.
  Native TextView Markdown rendering; no remote image loader is installed.
- CommonMark Java 0.13.0, Copyright 2015-2016 Atlassian Pty Ltd, BSD-2-Clause;
  `commonmark_BSD.txt`. Transitive Markdown parser dependency.
- Bouncy Castle bcpkix/bcprov/bcutil-jdk15to18 1.86, Legion of the Bouncy Castle Inc.,
  MIT-style Bouncy Castle License, `BouncyCastle_LICENSE.html`. Current official
  non-multi-release Java artifacts used for Android PKCS12/CMS digital signatures.
- PDF content-stream and signing adapters refer to Apache PDFBox
  `examples/src/main/java/org/apache/pdfbox/examples/util/RemoveAllText.java` and
  `examples/signature/CreateSignature.java` / `CreateSignatureBase.java` (Apache-2.0).
  Original PdfBox notices already bundled. Local modifications are documented in
  the new Kotlin file headers; no GPL/AGPL code was incorporated.
- AndroidX WorkManager 2.10.5 and Biometric 1.1.0, Copyright The Android Open Source
  Project, Apache-2.0. Direct library use for persisted scheduling and the system
  biometric prompt; `AndroidX_Apache-2.0.txt` is bundled. BC also supplies AES-GCM
  backup authentication; the custom envelope is documented in EncryptedBackup.kt.

## UI字体与补充图标

Noto Sans SC来自Google Fonts，OFL-1.1；字体固定版本及SHA256见ui-font.json，许可证见app/src/main/assets/third_party/NotoSansSC_OFL.txt。仅用于界面，PDF原字体保留。Google Material Design Icons（Apache-2.0）的原始path用于新增UI矢量，逐个来源见docs/UI_ICON_SOURCES.json。
