// UnsafeHelper
package scala.concurrent.stm.ccstm;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import scala.concurrent.stm.ccstm.CCSTMRefs.*;

/** Currently vars of Scala objects are compiled to fields of singleton
 *  instances, rather than static fields.  This prevents constant
 *  substitution by the HotSpot JIT of constants that are dynamically
 *  determined during static initialization.  Normally this is not a big deal,
 *  but in the case of the Unsafe object and field offsets the result is quite
 *  noticeable. 
 */
class UnsafeCCSTMRefs {

    static final Unsafe unsafe;

    static {
        Unsafe u = null;
        try {
            final Field f = AtomicInteger.class.getDeclaredField("unsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        }
        catch (final Exception xx) {
            // okay, CCSTMRefs will fall back to AtomicLongFieldUpdater
        }
        unsafe = u;
    }

    private static long getMetaOff(final Class<?> clazz) {
        if (unsafe == null)
            return 0L;

        try {
            return unsafe.objectFieldOffset(clazz.getDeclaredField("meta"));
        }
        catch (final Exception xx) {
            throw new RuntimeException("unexpected", xx);
        }
    }

    static final long booleanRefMetaOff = getMetaOff(UBooleanRef.class);
    static final long byteRefMetaOff = getMetaOff(UByteRef.class);
    static final long shortRefMetaOff = getMetaOff(UShortRef.class);
    static final long charRefMetaOff = getMetaOff(UCharRef.class);
    static final long intRefMetaOff = getMetaOff(UIntRef.class);
    static final long floatRefMetaOff = getMetaOff(UFloatRef.class);
    static final long longRefMetaOff = getMetaOff(ULongRef.class);
    static final long doubleRefMetaOff = getMetaOff(UDoubleRef.class);
    static final long genericRefMetaOff = getMetaOff(UGenericRef.class);    
}
