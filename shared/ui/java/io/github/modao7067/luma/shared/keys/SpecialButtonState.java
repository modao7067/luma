package io.github.modao7067.luma.shared.keys;

import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

/** The {@link Class} that maintains a state of a {@link SpecialButton} */
public class SpecialButtonState {

  /** If special button has been created for the {@link VirtualKeysView}. */
  boolean isCreated = false;
  /** If special button is active. */
  boolean isActive = false;
  /**
   * If special button is locked due to long hold on it and should not be deactivated if its state
   * is read.
   */
  boolean isLocked = false;
  boolean isHeld;
  boolean usedWhileHeld;

  List<Button> buttons = new ArrayList<>();

  VirtualKeysView mVirtualKeysView;

  public SpecialButtonState(VirtualKeysView extraKeysView) {
    mVirtualKeysView = extraKeysView;
  }

  public void setIsCreated(boolean value) {
    isCreated = value;
  }

  public void setIsActive(boolean value) {
    isActive = value;
    updateButtons();
  }

  public void setIsLocked(boolean value) {
    isLocked = value;
    updateButtons();
  }

  void setHeld(boolean value) { isHeld = value; updateButtons(); }

  boolean consume(boolean autoSetInactive) {
    if (!isCreated || (!isActive && !isHeld)) return false;
    if (isHeld && autoSetInactive) usedWhileHeld = true;
    if (autoSetInactive && !isLocked && !isHeld) setIsActive(false);
    return true;
  }

  private void updateButtons() {
    for (Button button : buttons) {
      button.setTextColor(
          (isActive || isHeld)
              ? (isLocked ? 0xff0d1821 : mVirtualKeysView.getButtonActiveTextColor())
              : mVirtualKeysView.getButtonTextColor());
      button.setPaintFlags(
          isLocked
              ? (button.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG)
              : (button.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG));
      mVirtualKeysView.applySpecialButtonBackground(button, isActive || isHeld, isLocked);
    }
  }
}
