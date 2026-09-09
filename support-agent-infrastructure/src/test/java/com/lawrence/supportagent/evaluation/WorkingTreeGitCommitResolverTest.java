package com.lawrence.supportagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证评测报告提交标识解析不依赖外部 Git 命令。 */
class WorkingTreeGitCommitResolverTest {
    @TempDir
    private Path directory;

    /** 验证普通仓库的松散分支引用。 */
    @Test
    void shouldResolveLooseHeadReference() throws Exception {
        Path git = Files.createDirectories(directory.resolve(".git").resolve("refs").resolve("heads"));
        Files.writeString(directory.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n",
                StandardCharsets.UTF_8);
        Files.writeString(git.resolve("main"), "0123456789abcdef0123456789abcdef01234567\n",
                StandardCharsets.UTF_8);

        assertEquals("0123456789abcdef0123456789abcdef01234567",
                new WorkingTreeGitCommitResolver(directory).resolve());
    }

    /** 验证损坏或缺失的仓库信息收敛为稳定占位值。 */
    @Test
    void shouldReturnUnknownForInvalidRepository() {
        assertEquals("unknown", new WorkingTreeGitCommitResolver(directory).resolve());
    }
}
