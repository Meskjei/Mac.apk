import org.json.JSONObject;
import java.util.Set;

public final class JsonKeySetSmoke {
    public static void main(String[] args) throws Exception {
        if (JSONObject.class.getMethod("keySet").getReturnType() != Set.class)
            throw new AssertionError("JNI keySet signature");
        var object = new JSONObject("{\"a\":1,\"b\":2}");
        Set<String> keys = object.keySet();
        if (!keys.equals(Set.of("a", "b"))) throw new AssertionError("keys missing");
        object.put("c", 3);
        if (!keys.contains("c")) throw new AssertionError("key set must be a live view");
        keys.remove("a");
        if (object.has("a")) throw new AssertionError("removal must update the object");
        keys.clear();
        if (object.length() != 0) throw new AssertionError("clear must update the object");
        System.out.println("JSONObject keySet JNI signature and live view passed");
    }
}
