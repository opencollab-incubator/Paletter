package fix.api;

import org.geysermc.paletter.annotation.Experimental;
import org.geysermc.paletter.annotation.Internal;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Every kind of member the dump has to list, or leave out. */
public class Widget<T extends Comparable<T>> extends Base<T> implements Comparable<Widget<T>>, Runnable {
    public static final int LIMIT = 4;
    public static final long BIG = 5000000000L;
    public static final boolean ON = true;
    public static final char LETTER = 'x';
    public static final String NAME = "a \"quoted\"\nname";
    public static final float RATIO = 0.5f;
    public static final Widget<String> SHARED = null;
    protected int level;
    public final List<? extends T> items = null;
    int packagePrivate;
    private int hidden;
    @Internal
    public int plumbing;

    public Widget() {
    }

    public Widget(String name, int... counts) {
    }

    protected Widget(T first) {
    }

    private Widget(long secret) {
    }

    @Internal
    public Widget(Map<String, T> internal) {
    }

    public <R> R map(Function<? super T, ? extends R> mapper, R fallback) throws IOException {
        return fallback;
    }

    public static <K, V extends List<K>> Map<K, V> group(V... values) {
        return null;
    }

    @Deprecated
    public void old() {
    }

    @Experimental
    public String[] experimental(int[][] matrix) {
        return null;
    }

    @Internal
    public void plumbing() {
    }

    @Override
    public T first() {
        return null;
    }

    protected void hook() {
    }

    void packagePrivate() {
    }

    private void hidden() {
    }

    public synchronized void sync() {
    }

    @Override
    public int compareTo(Widget<T> other) {
        return 0;
    }

    @Override
    public void run() {
        Runnable lambda = () -> hidden();
    }

    public static class Nested {
        public Nested() {
        }
    }

    protected static final class Guarded {
    }

    static class Hidden {
    }

    private static class Private {
    }

    public interface Callback {
        void call(String message);

        default void twice(String message) {
            call(message);
            call(message);
        }

        static Callback none() {
            return message -> { };
        }
    }
}
