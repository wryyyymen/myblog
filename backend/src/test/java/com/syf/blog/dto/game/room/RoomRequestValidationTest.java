package com.syf.blog.dto.game.room;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不启动 Spring，验证消息正文中的缺失字段与显式 false 能被区分。 */
class RoomRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void startAcceptsMissingLevelForCompatibilityAndRejectsOutOfRangeSelection() {
        StartGameRequest request = new StartGameRequest();
        request.setRoomId("current-room");
        assertTrue(validator.validate(request).isEmpty());
        request.setLevelId(5);
        assertTrue(validator.validate(request).isEmpty());
        request.setLevelId(0);
        assertEquals(1, validator.validate(request).size());
        request.setLevelId(6);
        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void explicitTrueAndFalseAreBothValidReadyValues() {
        PlayerReadyRequest request = new PlayerReadyRequest();
        request.setRoomId("current-room");
        request.setReady(false);
        assertTrue(validator.validate(request).isEmpty());

        request.setReady(true);
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void missingReadyFieldIsRejectedInsteadOfBeingTreatedAsFalse() {
        PlayerReadyRequest request = new PlayerReadyRequest();
        request.setRoomId("current-room");

        Set<ConstraintViolation<PlayerReadyRequest>> violations = validator.validate(request);

        assertEquals(1, violations.size());
        ConstraintViolation<PlayerReadyRequest> violation = violations.iterator().next();
        assertEquals("ready", violation.getPropertyPath().toString());
    }

    @Test
    void readyAndLeaveRejectMissingBlankAndOversizedRoomIds() {
        String[] invalidRoomIds = { null, "", "   ", "x".repeat(37) };

        for (String roomId : invalidRoomIds) {
            PlayerReadyRequest readyRequest = new PlayerReadyRequest();
            readyRequest.setRoomId(roomId);
            readyRequest.setReady(true);
            Set<ConstraintViolation<PlayerReadyRequest>> readyViolations = validator.validate(readyRequest);
            assertEquals(1, readyViolations.size());
            assertEquals("roomId", readyViolations.iterator().next().getPropertyPath().toString());

            LeaveRoomRequest leaveRequest = new LeaveRoomRequest();
            leaveRequest.setRoomId(roomId);
            Set<ConstraintViolation<LeaveRoomRequest>> leaveViolations = validator.validate(leaveRequest);
            assertEquals(1, leaveViolations.size());
            assertEquals("roomId", leaveViolations.iterator().next().getPropertyPath().toString());
        }
    }
}
