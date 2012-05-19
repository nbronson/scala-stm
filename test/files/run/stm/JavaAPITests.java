/* scala-stm - (c) 2009-2012, Stanford University, PPL */

import scala.concurrent.stm.*;
import static scala.concurrent.stm.japi.STM.*;

import java.util.concurrent.Callable;

import java.util.Map;
import java.util.Set;
import java.util.List;

public class JavaAPITests {

    static class TestException extends RuntimeException {
    }

    static void assertEquals(Object lhs, Object rhs) {
        if (lhs == null ? rhs != null : !lhs.equals(rhs)) {
            throw new Error(lhs + " was not equal to " + rhs);
        }
    }

    static void assertTrue(boolean pred) {
        assertEquals(pred, true);
    }

    static void assertFalse(boolean pred) {
        assertEquals(pred, false);
    }

    public static void createIntegerRef() {
        Ref.View<Integer> ref = newRef(0);
        int unboxed = ref.get();
        assertEquals(0, unboxed);
    }

    public static void atomicWithRunnable() {
        final Ref.View<Integer> ref = newRef(0);
        atomic(new Runnable() {
            public void run() {
                ref.set(10);
            }
        });
        int value = ref.get();
        assertEquals(10, value);
    }

    public static void atomicWithCallable() {
        final Ref.View<Integer> ref = newRef(0);
        int oldValue = atomic(new Callable<Integer>() {
            public Integer call() {
                return ref.swap(10);
            }
        });
        assertEquals(0, oldValue);
        int newValue = ref.get();
        assertEquals(10, newValue);
    }

    public static void failingTransaction() {
        final Ref.View<Integer> ref = newRef(0);
        boolean caught = false;
        try {
            atomic(new Runnable() {
                public void run() {
                    ref.set(10);
                    throw new TestException();
                }
            });
        } catch (TestException e) {
            int value = ref.get();
            assertEquals(0, value);
            caught = true;
        }
        assertTrue(caught);
    }

    public static void transformInteger() {
        Ref.View<Integer> ref = newRef(0);
        transform(ref, new Transformer<Integer>() {
            public Integer apply(Integer i) {
                return i + 10;
            }
        });
        int value = ref.get();
        assertEquals(10, value);
    }

    public static void getAndTransformInteger() {
        Ref.View<Integer> ref = newRef(0);
        int value = getAndTransform(ref, new Transformer<Integer>() {
            public Integer apply(Integer i) {
                return i + 10;
            }
        });
        assertEquals(0, value);
    }

    public static void transformAndGetInteger() {
        Ref.View<Integer> ref = newRef(0);
        int value = transformAndGet(ref, new Transformer<Integer>() {
            public Integer apply(Integer i) {
                return i + 10;
            }
        });
        assertEquals(10, value);
    }

    public static void incrementInteger() {
        Ref.View<Integer> ref = newRef(0);
        increment(ref, 10);
        int value = ref.get();
        assertEquals(10, value);
    }

    public static void incrementLong() {
        Ref.View<Long> ref = newRef(0L);
        increment(ref, 10L);
        long value = ref.get();
        assertEquals(10L, value);
    }

    public static void createAndUseTMap() {
        Map<Integer, String> map = newMap();
        map.put(1, "one");
        map.put(2, "two");
        assertEquals("one", map.get(1));
        assertEquals("two", map.get(2));
        assertTrue(map.containsKey(2));
        map.remove(2);
        assertFalse(map.containsKey(2));
    }

    public static void failingTMapTransaction() {
        final Map<Integer, String> map = newMap();
        boolean caught = false;
        try {
            atomic(new Runnable() {
                public void run() {
                    map.put(1, "one");
                    map.put(2, "two");
                    assertTrue(map.containsKey(1));
                    assertTrue(map.containsKey(2));
                    throw new TestException();
                }
            });
        } catch (TestException e) {
            assertFalse(map.containsKey(1));
            assertFalse(map.containsKey(2));
            caught = true;
        }
        assertTrue(caught);
    }

    public static void createAndUseTSet() {
        Set<String> set = newSet();
        set.add("one");
        set.add("two");
        assertTrue(set.contains("one"));
        assertTrue(set.contains("two"));
        assertEquals(2, set.size());
        set.add("one");
        assertEquals(2, set.size());
        set.remove("two");
        assertFalse(set.contains("two"));
        assertEquals(1, set.size());
    }

    public static void createAndUseTArray() {
        List<String> list = newArrayAsList(3);
        assertEquals(null, list.get(0));
        assertEquals(null, list.get(1));
        assertEquals(null, list.get(2));
        list.set(0, "zero");
        list.set(1, "one");
        list.set(2, "two");
        assertEquals("zero", list.get(0));
        assertEquals("one", list.get(1));
        assertEquals("two", list.get(2));
    }
}
