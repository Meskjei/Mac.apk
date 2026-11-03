import java.net.URLClassLoader;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import javax.net.ssl.HttpsURLConnection;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

public final class OkHttpHostVmSmoke {
    public static void main(String[] args) throws Exception {
        boolean host = "1".equals(System.getenv("MACAPK_HOST_VM_NAME"));
        if (!host) System.setProperty("java.vm.name", "Dalvik");
        android.security.MacApkConscryptProvider.install();
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()},
                OkHttpHostVmSmoke.class.getClassLoader())) {
            try {
                Class<?> platform = loader.loadClass("okhttp3.internal.platform.Platform");
                Object companion = platform.getField("Companion").get(null);
                Object implementation = companion.getClass().getMethod("get").invoke(companion);
                if (!host || !implementation.getClass().getName().contains("Jdk"))
                    throw new AssertionError("wrong TLS platform");
                Object client = loader.loadClass("okhttp3.OkHttpClient").getConstructor().newInstance();
                Object verifier = client.getClass().getMethod("hostnameVerifier").invoke(client);
                if (verifier == null || verifier != HttpsURLConnection.getDefaultHostnameVerifier()
                    && !verifier.getClass().getName().contains("OkHostnameVerifier"))
                    throw new AssertionError("hostname verification missing");
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, null, null);
                var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                managers.init((KeyStore)null);
                boolean rejectsEmptyChain = false;
                for (var manager : managers.getTrustManagers()) if (manager instanceof X509TrustManager trust) {
                    try { trust.checkServerTrusted(new X509Certificate[0], "RSA"); }
                    catch (java.security.cert.CertificateException | IllegalArgumentException expected) { rejectsEmptyChain = true; }
                }
                if (!rejectsEmptyChain) throw new AssertionError("invalid certificate chain accepted");
                System.out.println("actual OkHttp client constructed on host JDK TLS; hostname verifier retained and invalid chain rejected");
            } catch (ExceptionInInitializerError expected) {
                if (host) throw expected;
                System.out.println("Dalvik profile reproduces actual OkHttp Android TLS initialization failure");
            }
        }
    }
}
