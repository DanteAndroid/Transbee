<p align="center">
  <strong>WhisperIt</strong>
</p>

<p align="center">
  <a href="#english">English</a> · <a href="#中文">中文</a>
</p>

---

## English

**WhisperIt** is a **desktop** app for **speech-to-text**, **translation**, and **bilingual subtitle export** (SRT, VTT, TXT). 

### Features

- **Transcription** powered by [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (`whisper-cli`), with optional **VAD** for better segment boundaries  
- **Translation** via multiple engines: **Apple Translate** (macOS, on-device), **Google**, **DeepL**, and **OpenAI-compatible** custom LLM APIs  
- **Export**: subtitle format and content (original / translation / bilingual single or dual files)  

### Tech stack

- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) · [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) (Desktop / JVM)  

### Requirements

- **JDK** compatible with this project (Gradle will use a toolchain if configured; use a recent LTS such as **17+**).  
- **macOS** (recommended for full features): Xcode / Command Line Tools for building bundled native tools; **Apple Translate** requires macOS and downloaded system translation languages.  
- **Windows / Linux**: project build scripts support bundling **whisper-cli** (Windows zip) and native helpers where applicable; see `composeApp/build.gradle.kts` for platform-specific tasks.  

### Build & run

```bash
./gradlew :composeApp:compileKotlinJvm   # compile check
./gradlew :composeApp:run                # run the desktop app
```

On Windows:

```bat
.\gradlew.bat :composeApp:run
```

### Packaging

Compose Desktop can produce installers (e.g. **DMG / MSI / DEB** depending on OS). See Gradle tasks under the Compose Desktop plugin, for example:

```bash
./gradlew :composeApp:packageDistributionForCurrentOS
```

**JDK requirement:** packaging calls **`jpackage`**, which must exist in the JDK Gradle uses. The **Android Studio embedded JBR** often does **not** include `jpackage`, so `:composeApp:checkRuntime` may fail. Use a **full JDK 17+** (e.g. Eclipse Temurin): in Android Studio set **Gradle JDK** to that JDK, or set `JAVA_HOME` / `org.gradle.java.home` (see comments in `gradle.properties`).

Exact task names may vary by Compose version; use `./gradlew tasks --group="compose desktop"` or your IDE’s Gradle tool window to list them.

### License

This project is licensed under [**CC BY-ND 4.0**](https://creativecommons.org/licenses/by-nd/4.0/) ( [`LICENSE`](./LICENSE) ).

- You may **view**, **clone**, and **redistribute unmodified** copies with attribution.  
- You may **not** distribute **modified** versions of this work (no forks that change the code and are published as derivatives under this license).  

---

## 中文

**WhisperIt** 是一款**桌面端**应用，用于**视频转录、翻译与双语字幕导出**（SRT、VTT、TXT）。

### 功能概览

- **转写**：基于 [whisper.cpp](https://github.com/ggml-org/whisper.cpp)（`whisper-cli`），可选 **VAD**，使分句更贴近说话停顿  
- **翻译**：支持 **Apple 本机翻译**（仅 macOS）、**Google**、**DeepL**、以及 **OpenAI 兼容**的自定义大模型接口  
- **导出**：可选择字幕格式与内容（原文 / 译文 / 双语单文件或双文件等）  

### 技术栈

[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) · [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)（Desktop / JVM）  

### 环境要求

- 与本项目兼容的 **JDK**（建议使用 **17 及以上** LTS；若项目配置了 toolchain，以 Gradle 为准）。  
- **macOS**（完整功能推荐）：需 Xcode / 命令行工具以编译/同步部分原生组件；使用 **Apple 翻译** 需在系统设置中下载对应翻译语言。  
- **Windows / Linux**：构建脚本会按平台处理 **whisper-cli** 等二进制；细节见 `composeApp/build.gradle.kts`。  

### 构建与运行

```bash
./gradlew :composeApp:compileKotlinJvm   # 编译检查
./gradlew :composeApp:run                # 启动桌面应用
```

Windows：

```bat
.\gradlew.bat :composeApp:run
```

### 打包

可使用 Compose Desktop 生成安装包（如 **DMG / MSI / DEB**，视当前系统而定），例如：

```bash
./gradlew :composeApp:packageDistributionForCurrentOS
```

**JDK 要求：** 打包会使用 **`jpackage`**，所用 JDK 的 `bin` 目录中必须包含该工具。Android Studio 默认的 **Gradle JDK（嵌入式 JBR）** 往往**没有** `jpackage`，会出现 `:composeApp:checkRuntime` 失败。请改用**完整 JDK 17+**（如 Eclipse Temurin）：在 Android Studio 中将 **Gradle JDK** 指向该 JDK，或配置 `JAVA_HOME` / `org.gradle.java.home`（见仓库根目录 `gradle.properties` 内注释）。

具体任务名随 Compose 版本可能略有变化，可在 IDE 的 Gradle 面板中查看 **compose desktop** 相关任务，或执行 `./gradlew tasks` 检索。

### 许可证

本项目采用 [**CC BY-ND 4.0**](https://creativecommons.org/licenses/by-nd/4.0/)（ [`LICENSE`](./LICENSE)）。

- 可**浏览**、**克隆**，并在**不修改**作品内容的前提下**再分发**，且需保留署名。  
- **不得**基于本作品制作并**分发**演绎作品（例如公开传播修改后的源码版本）。  
