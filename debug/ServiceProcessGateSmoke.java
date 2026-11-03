import android.app.Service;
import android.app.ServiceRegistry;
import android.content.Intent;
import android.content.ComponentName;
import android.os.IBinder;
import com.macapk.loader.Boot;
import java.util.Map;

public final class ServiceProcessGateSmoke {
    public static class LocalService extends Service {
        static int created;
        @Override public void onCreate() { created++; }
        @Override public IBinder onBind(Intent intent) { return null; }
    }
    public static class RemoteService extends LocalService {
        static int created;
        @Override public void onCreate() { created++; }
    }
    public static void main(String[] args) throws Exception {
        var field = Boot.class.getDeclaredField("sForeignProcess");
        field.setAccessible(true);
        field.set(null, Map.of(RemoteService.class.getName(), "fixture:remote"));
        var remote = new Intent().setComponent(new ComponentName("fixture", RemoteService.class.getName()));
        if (!"1".equals(System.getenv("MACAPK_SERVICE_PROCESS_GATE"))) {
            if (!ServiceRegistry.resolves(null, remote)
                || ServiceRegistry.ensureLive(null, RemoteService.class.getName()) == null
                || RemoteService.created != 1) throw new AssertionError("opt-out changed existing service hosting");
            System.out.println("service process gate: opt-out preserves existing in-process hosting");
            return;
        }
        if (ServiceRegistry.resolves(null, remote)
            || ServiceRegistry.start(null, remote, false) != null
            || ServiceRegistry.ensureLive(null, RemoteService.class.getName()) != null)
            throw new AssertionError("unsupported remote service accepted");
        if (RemoteService.created != 0) throw new AssertionError("remote lifecycle ran in main process");
        var local = new Intent().setComponent(new ComponentName("fixture", LocalService.class.getName()));
        if (!ServiceRegistry.resolves(null, local)
            || ServiceRegistry.ensureLive(null, LocalService.class.getName()) == null
            || LocalService.created != 1) throw new AssertionError("local service regressed");
        System.out.println("service process gate: remote rejected before lifecycle; local service created once");
    }
}
