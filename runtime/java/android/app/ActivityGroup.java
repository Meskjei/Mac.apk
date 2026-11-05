package android.app;

/** Legacy type used by libraries to distinguish embedded activity hosts. */
@Deprecated
public class ActivityGroup extends Activity {
    public ActivityGroup() { this(true); }

    // shortcut: type checks only; implement LocalActivityManager before hosting embedded activities.
    public ActivityGroup(boolean singleActivityMode) {
        throw new UnsupportedOperationException("Embedded activities are not supported");
    }
}
