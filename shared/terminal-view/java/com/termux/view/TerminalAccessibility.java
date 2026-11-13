package com.termux.view;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/** A passive window-state helper does not need a full terminal text snapshot per update. */
final class TerminalAccessibility {
    static boolean needsTerminalText(AccessibilityManager manager) {
        if (manager == null || !manager.isEnabled()) return false;
        if (manager.isTouchExplorationEnabled()) return true;
        int textEvents = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if ((info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
                    || (info.feedbackType & (AccessibilityServiceInfo.FEEDBACK_SPOKEN | AccessibilityServiceInfo.FEEDBACK_BRAILLE)) != 0
                    || (info.eventTypes & textEvents) != 0) return true;
        }
        return false;
    }
}
