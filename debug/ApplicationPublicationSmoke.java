import android.app.ActivityThread;
import android.app.Application;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.concurrent.Callable;

public final class ApplicationPublicationSmoke {
    public static void main(String[] args) throws Exception {
        var thread = ActivityThread.currentActivityThread();
        var field = ActivityThread.class.getDeclaredField("mInitialApplication");
        field.setAccessible(true);
        if (field.get(thread) != null) throw new AssertionError("application published before construction");
        var application = new Application();
        thread.macapkPublishApplication(application);
        if (field.get(thread) != application || ActivityThread.currentApplication() != application
            || thread.getApplication() != application) throw new AssertionError("application views disagree");
        try { thread.macapkPublishApplication(null); throw new AssertionError(); }
        catch (NullPointerException expected) { }
        try (var workers = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 128; i++) calls.add(() -> field.get(thread) == application);
            for (var result : workers.invokeAll(calls)) if (!result.get()) throw new AssertionError("background publication");
        }
        if (field.get(thread) != application) throw new AssertionError("null publish changed application");
        System.out.println("Application publication: reflective field, accessors, background readers and null rejection passed");
    }
}
