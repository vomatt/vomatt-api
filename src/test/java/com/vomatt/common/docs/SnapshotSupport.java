package com.vomatt.common.docs;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compares generated documentation with its committed snapshot. With {@code -Dsnapshot.update=true}
 * the snapshot is rewritten instead (UTF-8, LF).
 */
final class SnapshotSupport {

    private SnapshotSupport() {
    }

    static void verify(Path file, String generated, String updateCommand) throws IOException {
        String expected = generated.replace("\r\n", "\n");
        if (!expected.endsWith("\n")) {
            expected += "\n";
        }
        if (Boolean.getBoolean("snapshot.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected, StandardCharsets.UTF_8);
            return;
        }
        String actual = Files.exists(file)
                ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")
                : null;
        if (!expected.equals(actual)) {
            fail(file + " is out of date. Run `" + updateCommand + "` and commit the diff.");
        }
    }
}
