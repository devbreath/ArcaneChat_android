package org.thoughtcrime.securesms.util;

import android.graphics.Color;
import android.text.Layout;
import android.text.Spannable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import org.thoughtcrime.securesms.R;

/**
 * Handles taps on links ({@link LongClickCopySpan}) for a text-selectable {@link TextView}.
 *
 * <p>Unlike {@code LinkMovementMethod}, this listener always returns {@code false} from {@link
 * #onTouch}, so the {@link TextView} keeps its own (ArrowKeyMovementMethod based) text-selection
 * handling. This avoids the conflict where clicking a link breaks subsequent partial text
 * selection.
 */
public class LinkTapTouchListener implements View.OnTouchListener {

  private final TextView textView;
  private LongClickCopySpan pressedSpan;
  private float downX;
  private float downY;
  private long downTime;
  private boolean longPressFired;

  public LinkTapTouchListener(TextView textView) {
    this.textView = textView;
  }

  @Override
  public boolean onTouch(View v, MotionEvent event) {
    int touchSlop = ViewConfiguration.get(textView.getContext()).getScaledTouchSlop();
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downX = event.getX();
        downY = event.getY();
        downTime = event.getDownTime();
        longPressFired = false;
        pressedSpan = findSpan(event.getX(), event.getY());
        if (pressedSpan != null) {
          pressedSpan.setHighlighted(
              true, ContextCompat.getColor(textView.getContext(), R.color.touch_highlight));
          textView.invalidate();
        }
        break;
      case MotionEvent.ACTION_MOVE:
        if (Math.abs(event.getX() - downX) > touchSlop
            || Math.abs(event.getY() - downY) > touchSlop) {
          clearHighlight();
        }
        if (event.getEventTime() - downTime >= ViewConfiguration.getLongPressTimeout()) {
          longPressFired = true;
        }
        break;
      case MotionEvent.ACTION_UP:
        if (!longPressFired && pressedSpan != null) {
          LongClickCopySpan toClick = findSpan(event.getX(), event.getY());
          LongClickCopySpan span = pressedSpan;
          clearHighlight();
          if (toClick != null && toClick == span) {
            span.onClick(textView);
          }
        } else {
          clearHighlight();
        }
        break;
      case MotionEvent.ACTION_CANCEL:
        clearHighlight();
        break;
    }
    // Always return false: let the TextView handle text selection itself.
    return false;
  }

  private void clearHighlight() {
    if (pressedSpan != null) {
      pressedSpan.setHighlighted(false, Color.TRANSPARENT);
      textView.invalidate();
      pressedSpan = null;
    }
  }

  private LongClickCopySpan findSpan(float x, float y) {
    CharSequence text = textView.getText();
    Layout layout = textView.getLayout();
    if (!(text instanceof Spannable) || layout == null) {
      return null;
    }

    int px = (int) x - textView.getTotalPaddingLeft() + textView.getScrollX();
    int py = (int) y - textView.getTotalPaddingTop() + textView.getScrollY();

    if (py < 0 || py > layout.getHeight()) {
      return null;
    }

    int line = layout.getLineForVertical(py);
    if (px < layout.getLineLeft(line) || px > layout.getLineRight(line)) {
      return null;
    }

    int off = layout.getOffsetForHorizontal(line, px);
    LongClickCopySpan[] spans = ((Spannable) text).getSpans(off, off, LongClickCopySpan.class);
    return spans.length > 0 ? spans[0] : null;
  }
}
