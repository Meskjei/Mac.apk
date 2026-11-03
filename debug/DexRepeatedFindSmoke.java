import dalvik.system.BaseDexClassLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.ArrayList;

public final class DexRepeatedFindSmoke {
    private static final class ChildFirst extends BaseDexClassLoader {
        ChildFirst(String path) { super(path, null, null, null); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            return name.startsWith("fixture.") || name.startsWith("fixture/")
                ? findClass(name) : super.loadClass(name, resolve);
        }
        Class<?> direct(String name) throws ClassNotFoundException { return findClass(name); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("DEX fixture required");
        ChildFirst loader = new ChildFirst(args[0]);
        Class<?> expected = loader.loadClass("fixture.DynamicValue");
        if (loader.direct("fixture/DynamicValue") != expected) throw new AssertionError("repeat definition");
        if (Class.forName("fixture.DynamicValue", false, loader) != expected) throw new AssertionError();
        try (var workers = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<Class<?>>>();
            for (int i = 0; i < 128; i++) calls.add(() -> loader.direct("fixture.DynamicValue"));
            for (var result : workers.invokeAll(calls))
                if (result.get() != expected) throw new AssertionError("concurrent definition");
        }
        if (new ChildFirst(args[0]).direct("fixture.DynamicValue") == expected)
            throw new AssertionError("loader isolation");
        try { loader.direct("fixture.Missing"); throw new AssertionError("fabricated class"); }
        catch (ClassNotFoundException expectedFailure) { }
        System.out.println("DEX repeat find: direct, child-first, concurrent, distinct loaders and missing class passed");
    }
}
