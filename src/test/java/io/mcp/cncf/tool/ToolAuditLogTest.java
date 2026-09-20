package io.mcp.cncf.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAuditLogTest {

    @Test
    @DisplayName("the argument preview cannot forge a second log line or close the quoted field")
    void previewIsOneQuotedLine() {
        String hostile = "kube\" source=evil\n2026-01-01 INFO tool=refresh_cncf_data x\r\ty";

        String preview = ToolAuditLog.preview(hostile);

        assertThat(preview).doesNotContain("\"").doesNotContain("\n").doesNotContain("\r")
                .doesNotContain(" ").doesNotContain("\t");
        assertThat(preview).startsWith("kube  source=evil");
    }

    @Test
    @DisplayName("the preview is clipped and null is tolerated")
    void previewIsClipped() {
        assertThat(ToolAuditLog.preview("k".repeat(500))).hasSize(63).endsWith("...");
        assertThat(ToolAuditLog.preview(null)).isEmpty();
    }
}
