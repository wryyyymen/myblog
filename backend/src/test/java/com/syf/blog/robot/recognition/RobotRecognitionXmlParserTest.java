package com.syf.blog.robot.recognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RobotRecognitionXmlParserTest {
    private final RobotRecognitionXmlParser parser = new RobotRecognitionXmlParser();

    @Test
    void parsesCurrentGrammarResult() {
        ParsedRobotRecognition result = parser.parse("""
                <?xml version="1.0" encoding="utf-8"?>
                <nlp>
                  <rawtext>你好</rawtext>
                  <result>
                    <confidence>97</confidence>
                    <object><cmd id="2">你好</cmd></object>
                  </result>
                </nlp>
                """);

        assertEquals("你好", result.text());
        assertEquals(2, result.commandId());
        assertEquals(97, result.confidence());
    }

    @Test
    void lowConfidenceKeepsTextButNotActionableCommand() {
        ParsedRobotRecognition result = parser.parse(xml("29", "44", "天外来物"));

        assertEquals("天外来物", result.text());
        assertNull(result.commandId());
        assertEquals(29, result.confidence());
    }

    @Test
    void missingConfidenceOrCommandKeepsTextButNotActionableCommand() {
        ParsedRobotRecognition missingConfidence = parser.parse("""
                <nlp><rawtext>你好</rawtext><result><object><cmd id="2">你好</cmd></object></result></nlp>
                """);
        assertEquals("你好", missingConfidence.text());
        assertNull(missingConfidence.commandId());
        assertNull(missingConfidence.confidence());

        ParsedRobotRecognition missingCommand = parser.parse("""
                <nlp><rawtext>没有匹配的命令</rawtext><result><confidence>95</confidence></result></nlp>
                """);
        assertEquals("没有匹配的命令", missingCommand.text());
        assertNull(missingCommand.commandId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648", "0", "-1"})
    void rejectsInvalidCommandIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(xml("100", id, "你好")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "unknown"})
    void rejectsInvalidConfidence(String confidence) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(xml(confidence, "2", "你好")));
    }

    @Test
    void rejectsExternalEntitiesAndMalformedOrAmbiguousDocuments() {
        String externalEntity = """
                <!DOCTYPE nlp [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <nlp><rawtext>&secret;</rawtext><result><confidence>100</confidence></result></nlp>
                """;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(externalEntity));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("<nlp><result>"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("<nlp><result/><result/><rawtext>你好</rawtext></nlp>"));
    }

    @Test
    void rejectsMissingTextAndOversizedResult() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("<nlp><result><confidence>100</confidence></result></nlp>"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("x".repeat(16_385)));
    }

    private static String xml(String confidence, String id, String text) {
        return "<nlp><rawtext>" + text + "</rawtext><result><confidence>" + confidence
                + "</confidence><object><cmd id=\"" + id + "\">" + text
                + "</cmd></object></result></nlp>";
    }
}
