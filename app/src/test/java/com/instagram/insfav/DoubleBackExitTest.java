package com.instagram.insfav;

import org.junit.Test;
import static org.junit.Assert.*;

public class DoubleBackExitTest {
    @Test public void firstBackWarnsAndSecondWithinTwoSecondsExits() {
        DoubleBackExit guard = new DoubleBackExit();
        assertFalse(guard.press(0));
        assertTrue(guard.press(1500));
    }

    @Test public void expiredConfirmationRequiresAnotherPress() {
        DoubleBackExit guard = new DoubleBackExit();
        assertFalse(guard.press(100));
        assertFalse(guard.press(2101));
        assertTrue(guard.press(2200));
    }

    @Test public void exactBoundaryIsAcceptedAndExitDoesNotRemainArmed() {
        DoubleBackExit guard = new DoubleBackExit();
        assertFalse(guard.press(100));
        assertTrue(guard.press(2100));
        assertFalse(guard.press(2200));
    }

    @Test public void navigationOrBackgroundResetPreventsAccidentalExit() {
        DoubleBackExit guard = new DoubleBackExit();
        assertFalse(guard.press(100));
        guard.reset();
        assertFalse(guard.press(200));
        assertTrue(guard.press(300));
    }

    @Test public void clockRollbackCannotTriggerExit() {
        DoubleBackExit guard = new DoubleBackExit();
        assertFalse(guard.press(100));
        assertFalse(guard.press(99));
    }
}
