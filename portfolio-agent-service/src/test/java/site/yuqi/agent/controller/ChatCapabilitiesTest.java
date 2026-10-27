package site.yuqi.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCapabilitiesTest {
    @Test
    void capabilitiesExposeOnlyTheTwoPublicFeatureSwitches() {
        var controller = new ChatController(null, null);
        ReflectionTestUtils.setField(controller, "deepEnabled", false);
        ReflectionTestUtils.setField(controller, "attachmentsEnabled", false);
        assertThat(controller.capabilities()).containsOnlyKeys("deep", "attachments")
                .containsEntry("deep", false).containsEntry("attachments", false);
    }
}
