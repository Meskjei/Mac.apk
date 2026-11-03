import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

public final class ImeFocusTransferSmoke {
    public static void main(String[] args) {
        var root = new FrameLayout(null);
        var fields = new EditText[6];
        var imm = InputMethodManager.peekInstance();
        for (int i = 0; i < fields.length; i++) {
            fields[i] = new EditText(null);
            fields[i].setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});
            root.addView(fields[i]);
        }
        for (int i = 0; i < fields.length - 1; i++) {
            final EditText next = fields[i + 1];
            fields[i].addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(Editable s) { if (s.length() == 1) next.requestFocus(); }
            });
        }
        imm.showSoftInput(fields[0], 0);
        for (int i = 0; i < fields.length; i++) {
            if (InputMethodManager.macapkActiveInput() != fields[i])
                throw new AssertionError("keyboard retained old field at index " + i);
            InputMethodManager.macapkActiveInput().macapkHandleSoftKey('1' + i, 0);
            if (!fields[i].getText().toString().equals(String.valueOf((char)('1' + i))))
                throw new AssertionError("character not delivered to current field");
        }
        var other = new View(null);
        other.setFocusableInTouchMode(true);
        root.addView(other);
        other.requestFocus();
        if (InputMethodManager.macapkActiveInput() != null)
            throw new AssertionError("non-editor stole text input");
        fields[0].requestFocus();
        if (InputMethodManager.macapkActiveInput() != fields[0]) throw new AssertionError("refocus lost IME");
        imm.hideSoftInputFromWindow(null, 0);
        fields[1].requestFocus();
        if (InputMethodManager.macapkActiveInput() != null) throw new AssertionError("explicit hide ignored");
        imm.showSoftInput(fields[2], 0);
        root.removeView(fields[2]);
        if (InputMethodManager.macapkActiveInput() != null) throw new AssertionError("detached editor remained active");
        System.out.println("IME focus transfer: six one-character fields, non-editor, refocus, hide and detach passed");
    }
}
