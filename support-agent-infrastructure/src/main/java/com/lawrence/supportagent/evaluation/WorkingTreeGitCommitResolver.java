package com.lawrence.supportagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 只读解析当前工作树 HEAD，用于评测报告复现信息。 */
public class WorkingTreeGitCommitResolver {
    private final Path repositoryDirectory;

    /** 使用当前进程目录作为 Git 仓库根目录。 */
    public WorkingTreeGitCommitResolver() { this(Path.of(".")); }

    /** 注入可测试的仓库目录。 */
    public WorkingTreeGitCommitResolver(Path repositoryDirectory) {
        this.repositoryDirectory = repositoryDirectory.toAbsolutePath().normalize();
    }

    /** 返回完整提交哈希；无法解析时返回稳定的 unknown，不执行外部命令。 */
    public String resolve() {
        try {
            Path gitDirectory = gitDirectory();
            String head = Files.readString(gitDirectory.resolve("HEAD"), StandardCharsets.UTF_8).trim();
            if (!head.startsWith("ref: ")) return validHash(head).orElse("unknown");
            String reference = head.substring(5).trim();
            Path looseReference = gitDirectory.resolve(reference);
            if (Files.isRegularFile(looseReference)) {
                return validHash(Files.readString(looseReference, StandardCharsets.UTF_8).trim())
                        .orElse("unknown");
            }
            return packedReference(gitDirectory, reference).orElse("unknown");
        } catch (IOException | RuntimeException exception) {
            return "unknown";
        }
    }

    /** 解析普通仓库目录或 Git worktree 的 gitdir 指针。 */
    private Path gitDirectory() throws IOException {
        Path marker = repositoryDirectory.resolve(".git");
        if (Files.isDirectory(marker)) return marker;
        String pointer = Files.readString(marker, StandardCharsets.UTF_8).trim();
        if (!pointer.startsWith("gitdir: ")) throw new IOException("无法识别 .git 指针");
        Path value = Path.of(pointer.substring(8).trim());
        return value.isAbsolute() ? value.normalize() : repositoryDirectory.resolve(value).normalize();
    }

    /** 从 packed-refs 查找指定引用。 */
    private Optional<String> packedReference(Path gitDirectory, String reference) throws IOException {
        Path packed = gitDirectory.resolve("packed-refs");
        if (!Files.isRegularFile(packed)) return Optional.empty();
        return Files.readAllLines(packed, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith("#") && !line.startsWith("^"))
                .map(line -> line.split(" ", 2))
                .filter(parts -> parts.length == 2 && reference.equals(parts[1]))
                .map(parts -> validHash(parts[0]).orElse(null))
                .filter(java.util.Objects::nonNull).findFirst();
    }

    /** 仅接受完整 SHA-1 或 SHA-256 十六进制提交哈希。 */
    private Optional<String> validHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
                ? Optional.of(value.toLowerCase(java.util.Locale.ROOT)) : Optional.empty();
    }
}
