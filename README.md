# Transbee

[English](#english) | [中文](#chinese)

## English

Transbee is a desktop app for speech-to-text, translation, bilingual subtitle export (SRT, VTT, TXT),
and document recognition and translation (PDF and more). It combines transcription, multi-engine
translation, and flexible export in one workflow—broadly capable and versatile, for everyday audio/video
and documents as well as workflows that need consistent terminology.

### Features

- Transcription: based on [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (`whisper-cli`), with optional VAD (voice activity detection) for better segmentation  
- Professional glossary & correction: an AI-powered correction system—fast and efficient  
- Translation: Apple Translate (macOS, native on-device), Google, DeepL, Google Gemini, and custom LLM APIs compatible with the OpenAI protocol  
- Document processing: recognition and translation for PDF and other formats (document recognition services can be integrated)  
- Export: subtitle formats (SRT, VTT, TXT) and content options (source / translation / bilingual)

### Tech stack

- [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) (Desktop / JVM)

### Run and build

```bash
./gradlew :composeApp:run
```

Generate installers:

```bash
./gradlew :composeApp:packageDistributionForCurrentOS
```

### Troubleshooting

#### macOS Build Failure (AppleTranslate)

If you encounter an error like `Process 'command 'swift'' finished with non-zero exit value 1` during `:composeApp:buildAppleTranslateMac`, it is often due to a stale Swift build cache after moving or renaming the project directory.

Solution: run the following command to clean the Swift package cache:

```bash
cd native/AppleTranslate && swift package clean && cd ../..
```

Then retry the build.

### License

This project is licensed under [CC BY-ND 4.0](https://creativecommons.org/licenses/by-nd/4.0/) (`[LICENSE](./LICENSE)`).

- You may view, clone, and redistribute unmodified copies with attribution.  
- You may not distribute modified versions of this work (no forks that change the code and are published as derivatives under this license).

---

## 中文

Transbee 是一款桌面端应用，支持语音转文字、翻译、双语字幕导出 (SRT, VTT, TXT) 以及文档识别与翻译（PDF 等）。在一条工作流中整合转录、多引擎翻译与灵活导出，用途广、能力强，既适合日常音视频与文档，也胜任对术语一致有更高要求的工作。

### 功能特点

- 转录：基于 [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (`whisper-cli`)，支持可选的 VAD（语音活动检测）以获得更好的断句效果。  
- 专业词库纠错功能：AI 智能纠错系统，高效快速。  
- 翻译：Apple 翻译（macOS 原生离线）、Google、DeepL、Google Gemini，以及兼容 OpenAI 协议的自定义大模型 API。  
- 文档处理：支持 PDF 等多种文档格式的识别与翻译（可接入文档识别服务）。  
- 导出：支持多种字幕格式 (SRT, VTT, TXT) 及内容选择（原文 / 译文 / 双语）。

### 技术栈

- [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) (Desktop / JVM)

### 运行与构建

```bash
./gradlew :composeApp:run
```

生成安装包：

```bash
./gradlew :composeApp:packageDistributionForCurrentOS
```

### 常见问题排查

#### macOS 构建失败 (AppleTranslate)

如果在执行 `:composeApp:buildAppleTranslateMac` 时遇到 `Process 'command 'swift'' finished with non-zero exit value 1` 错误，通常是因为移动或重命名了项目目录导致 Swift 构建缓存失效。

解决方法：执行以下命令清理 Swift 包缓存：

```bash
cd native/AppleTranslate && swift package clean && cd ../..
```

然后重新尝试构建。

### 开源许可

本项目采用 [CC BY-ND 4.0](https://creativecommons.org/licenses/by-nd/4.0/) 许可协议 (`[LICENSE](./LICENSE)`)。

- 您可以阅读、克隆并在保持署名的前提下重新分发未经修改的副本。  
- 您不得分发此作品的修改版本（即不支持发布修改代码后的衍生版本）。

