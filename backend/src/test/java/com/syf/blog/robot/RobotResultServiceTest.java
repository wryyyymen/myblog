package com.syf.blog.robot;

import com.syf.blog.exception.BusinessException;
import com.syf.blog.robot.recognition.RobotRecognitionXmlParser;
import com.syf.blog.robot.service.RobotEventService;
import com.syf.blog.robot.service.RobotResultService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotResultServiceTest {
    private final RobotEventService events = new RobotEventService();
    private final RobotResultService results = new RobotResultService(new RobotRecognitionXmlParser(), events);

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    void completeXmlPublishesRecognizedCommand() {
        var event = results.acceptXml(xml(97).getBytes(StandardCharsets.UTF_8));

        assertEquals("你好", event.text());
        assertEquals(2, event.commandId());
        assertEquals(97, event.confidence());
        assertEquals(event, events.latest().orElseThrow());
    }

    @Test
    void lowConfidenceStillPublishesTextWithoutCommand() {
        var event = results.acceptXml(xml(20).getBytes(StandardCharsets.UTF_8));

        assertEquals("你好", event.text());
        assertNull(event.commandId());
        assertEquals(20, event.confidence());
    }

    @Test
    void malformedAndOversizedPayloadsDoNotPublish() {
        BusinessException invalid = assertThrows(BusinessException.class,
                () -> results.acceptXml("<nlp/>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatus());

        BusinessException oversized = assertThrows(BusinessException.class,
                () -> results.acceptXml(new byte[16 * 1024 + 1]));
        assertEquals(HttpStatus.valueOf(413), oversized.getStatus());
        assertTrue(events.latest().isEmpty());
    }

    private static String xml(int confidence) {
        return "<nlp><rawtext>你好</rawtext><result><confidence>" + confidence
                + "</confidence><object><cmd id=\"2\">你好</cmd></object></result></nlp>";
    }
}
