import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;

/** Run with debug/main_looper_fd_fixture.cpp; the fixture is never an app dependency. */
public final class MainLooperFdFairnessSmoke {
    static volatile int delivered;
    static native void signal();

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        var ctor = MessageQueue.class.getDeclaredConstructor(boolean.class);
        ctor.setAccessible(true);
        MessageQueue queue = ctor.newInstance(true);
        var enqueue = MessageQueue.class.getDeclaredMethod("enqueueMessage", Message.class, long.class);
        var quit = MessageQueue.class.getDeclaredMethod("quit");
        enqueue.setAccessible(true);
        quit.setAccessible(true);
        Message first = Message.obtain();
        first.callback = MainLooperFdFairnessSmoke::signal;
        Message second = Message.obtain();
        boolean[] servicedBeforeNextMessage = {false};
        second.callback = () -> {
            servicedBeforeNextMessage[0] = delivered == 1;
            try { quit.invoke(queue); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        };
        enqueue.invoke(queue, first, 1L);
        enqueue.invoke(queue, second, 2L);
        var loop = Looper.class.getDeclaredMethod("runRealMainLoopNative", MessageQueue.class, String.class, long.class);
        loop.setAccessible(true);
        loop.invoke(null, queue, Thread.currentThread().getName(), 1L);
        if (!servicedBeforeNextMessage[0])
            throw new AssertionError("ready native fd was starved until the main Java queue drained");
        System.out.println("main looper: ready native fd serviced between consecutive Java messages");
    }
}
