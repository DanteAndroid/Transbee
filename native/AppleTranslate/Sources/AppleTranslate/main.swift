import Foundation
@preconcurrency import Translation

private struct InPayload: Codable {
    let texts: [String]
    let source: String
    let target: String
}

private struct OutPayload: Codable {
    var translations: [String]?
    var error: String?
}

private final class RunGate: @unchecked Sendable {
    private let lock = NSLock()
    private var finished = false

    func finishOnce() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if finished { return false }
        finished = true
        return true
    }
}

private let runGate = RunGate()

private func writeOutput(_ path: String, _ payload: OutPayload) {
    guard !path.isEmpty else { return }
    let enc = (try? JSONEncoder().encode(payload)) ?? Data()
    try? enc.write(to: URL(fileURLWithPath: path), options: .atomic)
}

@main
struct AppleTranslateCli {
    static func main() async {
        let args = CommandLine.arguments
        guard args.count >= 3 else {
            fputs("用法: AppleTranslate input.json output.json\n", stderr)
            exit(1)
        }

        let inPath = args[1]
        let outPath = args[2]
        writeOutput(outPath, OutPayload(translations: nil, error: "pending"))

        Task.detached {
            try await Task.sleep(for: .seconds(15))
            if runGate.finishOnce() {
                writeOutput(outPath, OutPayload(translations: nil, error: "Swift 侧超时（15s），系统翻译未在时限内完成。"))
                exit(124)
            }
        }

        do {
            let data = try Data(contentsOf: URL(fileURLWithPath: inPath))
            let decoded = try JSONDecoder().decode(InPayload.self, from: data)
            if decoded.source.caseInsensitiveCompare(decoded.target) == .orderedSame {
                if runGate.finishOnce() {
                    writeOutput(outPath, OutPayload(translations: decoded.texts, error: nil))
                    exit(0)
                }
                return
            }
            if decoded.texts.isEmpty {
                if runGate.finishOnce() {
                    writeOutput(outPath, OutPayload(translations: [], error: nil))
                    exit(0)
                }
                return
            }

            let source = Locale.Language(identifier: decoded.source)
            let target = Locale.Language(identifier: decoded.target)
            let session = TranslationSession(installedSource: source, target: target)
            let isReady = await session.isReady
            if !isReady {
                let canRequestDownloads = session.canRequestDownloads
                let hint = canRequestDownloads
                    ? "系统需要下载语言包，但当前无交互下载流程。请先在系统翻译中手动安装该语言对后重试。"
                    : "该语言对未安装，且当前会话不可请求下载。请先在系统翻译中手动安装该语言对后重试。"
                if runGate.finishOnce() {
                    writeOutput(outPath, OutPayload(translations: nil, error: hint))
                    exit(2)
                }
                return
            }

            var out: [String] = []
            out.reserveCapacity(decoded.texts.count)
            for t in decoded.texts {
                let resp = try await session.translate(t)
                out.append(resp.targetText)
            }
            if runGate.finishOnce() {
                writeOutput(outPath, OutPayload(translations: out, error: nil))
                exit(0)
            }
        } catch {
            if runGate.finishOnce() {
                writeOutput(outPath, OutPayload(translations: nil, error: String(describing: error)))
                exit(1)
            }
        }
    }
}
