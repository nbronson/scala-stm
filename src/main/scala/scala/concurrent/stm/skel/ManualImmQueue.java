package scala.concurrent.stm.skel;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

public class ManualImmQueue {
//    class ImmQueue[A](left: Stream[A], right: Stream[A], forcing: Stream[A]) {
//
//      def append(v: A) = makeq(left, v #:: right, forcing)
//      def :+(v: A) = append(v)
//
//      def head = left.head
//
//      def tail = makeq(left.tail, right, forcing)
//
//      private def makeq(left: Stream[A], right: Stream[A], forcing: Stream[A]): ImmQueue[A] = {
//        if (forcing.isEmpty) {
//          val newLeft = rot(left, right, Stream.empty[A])
//          new ImmQueue(newLeft, Stream.empty[A], newLeft)
//        } else {
//          new ImmQueue(left, right, forcing.tail)
//        }
//      }
//
//      private def rot(left: Stream[A], right: Stream[A], accum: Stream[A]): Stream[A] = {
//        if (left.isEmpty) {
//          right.head #:: accum
//        } else {
//          left.head #:: rot(left.tail, right.tail, right.head #:: accum)
//        }
//      }
//    }
//
//    interface Stream<A> {
//        boolean isEmpty();
//        A head();
//        Stream<A> tail();
//    }
//
//    static class Nil<A> implements Stream<A> {
//        public boolean isEmpty() { return true; }
//        public A head() { throw new IllegalStateException(); }
//        public Stream<A> tail() { throw new IllegalStateException(); }
//    }
//
//    static class Concrete<A> implements Stream<A> {
//        final A _head;
//        final Stream<A> _tail;
//
//        Concrete(final A head, final Stream<A> tail) {
//            _head = head;
//            _tail = tail;
//        }
//
//        public boolean isEmpty() { return false; }
//        public A head() { return _head; }
//        public Stream<A> tail() { return _tail; }
//    }
//
//    static class PendingRot<A> implements Stream<A> {
//        final Stream<A> _left;
//        final Stream<A> _right;
//        final Stream<A> _accum;
//        Concrete<A> _concrete;
//
//        PendingRot(final Stream<A> left, final Stream<A> right, final Stream<A> accum) {
//            _left = left;
//            _right = right;
//            _accum = accum;
//        }
//
//        Concrete<A> concrete() {
//            Concrete<A> memoized = _concrete;
//            if (memoized == null) {
//                memoized = eval();
//                _concrete = memoized;
//            }
//            return memoized;
//        }
//
//        private Concrete<A> eval() {
//            if (_left.isEmpty()) {
//                return new Concrete<A>(_right.head(), _accum);
//            } else {
//                return new Concrete<A>(
//                        _left.head(),
//                        new PendingRot<A>(_left.tail(), _right.tail(), new Concrete<A>(_right.head(), _accum)));
//            }
//        }
//
//        public boolean isEmpty() { return false; }
//        public A head() { return concrete().head(); }
//        public Stream<A> tail() { return concrete().tail(); }
//    }
//
//    private static int nodeSize(final Node<?> node) {
//        return node == null ? 0 : node.size();
//    }
//
//    private static <A> Node<A> rot(final Node<A> left, final Node<A> right, final Node<A> accum) {
//        final int accumSize = nodeSize(accum);
//        if (left == null) {
//            assert(right.size() == 1);
//            return new Node<A>(1 + accumSize, right.head(), accum);
//        } else if (right.size() == 1) {
//            // The addition of prepend means that we might get ahead of
//            // ourself when forcing (unlike Okasaki's original).  This is a
//            // new case
//            return new Node<A>(
//                    left.size() + 1 + accumSize,
//                    left.head(),
//                    new PendingRot<A>(
//                            left.tail(),
//                            right,
//                            accum));
//        } else {
//            return new Node<A>(
//                    left.size() + right.size() + accumSize,
//                    left.head(),
//                    new PendingRot<A>(
//                            left.tail(),
//                            right.tail(),
//                            new Node<A>(1 + accumSize, right.head(), accum)));
//        }
//    }
//
//    static class Node<A> extends AtomicReference<Object> {
//        final int _size;
//        final A _head;
//
//        Node(final int size, final A head, final Object tail) {
//            super(tail);
//            _size = size;
//            _head = head;
//        }
//
//        public int size() {
//            return _size;
//        }
//
//        public A get(final int index) {
//            Node<A> cur = this;
//            for (int i = 0; i < index; ++i) {
//                cur = cur.tail();
//            }
//            return cur.head();
//        }
//
//        public A head() {
//            return _head;
//        }
//
//        @SuppressWarnings("unchecked")
//        public Node<A> tail() {
//            Object t = get();
//            if (t != null && (t instanceof PendingRot<?>)) {
//                t = ((PendingRot<A>) t).eval();
//                lazySet(t);
//            }
//            return (Node<A>) t;
//        }
//    }
//
//    static class PendingRot<A> {
//        final Node<A> _left;
//        final Node<A> _right;
//        final Node<A> _accum;
//
//        PendingRot(final Node<A> left, final Node<A> right, final Node<A> accum) {
//            _left = left;
//            _right = right;
//            _accum = accum;
//        }
//
//        Node<A> eval() {
//            return rot(_left, _right, _accum);
//        }
//    }
//
//    static <A> Queue<A> empty() {
//        return new Queue<A>(null, null, null);
//    }
//
//    static class Queue<A> {
//        final Node<A> _left;
//        final Node<A> _right;
//        final Node<A> _forcing;
//
//        // We can use a racy cache here (as opposed to inside Node) because
//        // the reference is to an object that contains only final fields
//        // (except for the cache itself, which is okay if we don't see it).
//        Queue<A> _tailCache;
//
//        Queue(final Node<A> left, final Node<A> right, final Node<A> forcing) {
//            _left = left;
//            _right = right;
//            _forcing = forcing;
//        }
//
//        public int size() {
//            return nodeSize(_left) + nodeSize(_right);
//        }
//
//        public boolean isEmpty() {
//            return _left == null;
//        }
//
//        public A get(final int index) {
//            if (index < _left.size()) {
//                return _left.get(index);
//            } else {
//                return _right.get(size() - 1 - index);
//            }
//        }
//
//        public A head() {
//            return _left.head();
//        }
//
//        public Queue<A> tail() {
//            Queue<A> t = _tailCache;
//            if (t == null) {
//                t = make(_left.tail(), _right, _forcing);
//                _tailCache = t;
//            }
//            return t;
//        }
//
//        public Queue<A> prepend(final A v) {
//            return new Queue<A>(new Node<A>(1 + nodeSize(_left), v, _left), _right, _forcing);
//        }
//
//        public Queue<A> append(final A v) {
//            return make(_left, new Node<A>(1 + nodeSize(_right), v, _right), _forcing);
//        }
//
//        private Queue<A> make(final Node<A> left, final Node<A> right, final Node<A> forcing) {
//            if (forcing == null) {
//                final Node<A> newLeft = rot(left, right, null);
//                return new Queue<A>(newLeft, null, newLeft);
//            } else {
//                return new Queue<A>(left, right, forcing.tail());
//            }
//        }
//    }

    // expects length(left) + 1 == length(right)
    private static <A> Node<A> rot(final Node<A> left, final Node<A> right, final Node<A> accum) {
        if (left == null) {
            assert(right.tail() == null);
            return new Node<A>(right.head(), accum);
        } else {
            return new Node<A>(
                    left.head(),
                    new PendingRot<A>(
                            left.tail(),
                            right.tail(),
                            new Node<A>(right.head(), accum)));
        }
    }

    private static class Node<A> extends AtomicReference<Object> {
        final A _head;

        Node(final A head, final Object tail) {
            super(tail);
            _head = head;
        }

        public A get(final int index) {
            Node<A> cur = this;
            for (int i = 0; i < index; ++i) {
                cur = cur.tail();
            }
            return cur.head();
        }

        public A head() {
            return _head;
        }

        @SuppressWarnings("unchecked")
        public Node<A> tail() {
            Object t = get();
            if (t != null && (t instanceof PendingRot<?>)) {
                t = ((PendingRot<A>) t).eval();
                lazySet(t);
            }
            return (Node<A>) t;
        }
    }

    private static class PendingRot<A> {
        final Node<A> _left;
        final Node<A> _right;
        final Node<A> _accum;

        PendingRot(final Node<A> left, final Node<A> right, final Node<A> accum) {
            _left = left;
            _right = right;
            _accum = accum;
        }

        Node<A> eval() {
            return rot(_left, _right, _accum);
        }
    }

    private static class QueueIterator<A> implements Iterator<A> {
        Node<A> _left;
        Node<A> _right;
        Node<A> _accum;

        QueueIterator(final Node<A> left, final Node<A> right) {
            _left = left;
            _right = right;
        }

        public boolean hasNext() {
            return _left != null;
        }

        public A next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            final A result = _left.head();
            _left = _left.tail();

            if (_right != null) {
                _accum = new Node<A>(_right.head(), _accum);
                _right = _right.tail();
            }
            if (_left == null) {
                assert(_right == null);
                _left = _accum;
                _accum = null;
            }

            return result;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static <A> Queue<A> makeAndForce(final Node<A> left,
                                             final int leftSize,
                                             final Node<A> right,
                                             final int rightSize,
                                             final Node<A> forcing) {
        if (rightSize == leftSize + 1) {
            final Node<A> newLeft = rot(left, right, null);
            return new Queue<A>(newLeft, leftSize + rightSize, null, 0, newLeft);
        } else {
            assert(rightSize <= leftSize);
            return new Queue<A>(
                    left,
                    leftSize,
                    right,
                    rightSize,
                    forcing == null ? null : forcing.tail());
        }
    }

    public static class Queue<A> {
        final Node<A> _left;
        final int _leftSize;
        final Node<A> _right;
        final int _rightSize;
        final Node<A> _forcing;

        public Queue() {
            this(null, 0, null, 0, null);
        }

        private Queue(final Node<A> left,
                      final int leftSize,
                      final Node<A> right,
                      final int rightSize,
                      final Node<A> forcing) {
            _left = left;
            _leftSize = leftSize;
            _right = right;
            _rightSize = rightSize;
            _forcing = forcing;
        }

        public int size() {
            return _leftSize + _rightSize;
        }

        public boolean isEmpty() {
            return _left == null;
        }

        public A get(final int index) {
            if (index < _leftSize) {
                return _left.get(index);
            } else {
                return _right.get(size() - 1 - index);
            }
        }

        public A head() {
            return _left.head();
        }

        public Queue<A> tail() {
            return makeAndForce(_left.tail(), _leftSize - 1, _right, _rightSize, _forcing);
        }

        public Queue<A> drop(int n) {
            Node<A> left = _left;
            int leftSize = _leftSize;
            Node<A> right = _right;
            int rightSize = _rightSize;
            Node<A> forcing = _forcing;

            while (n > 0) {
                n--;

                // inline tail()
                left = left.tail();
                leftSize--;

                // inline makeAndForce
                if (rightSize == leftSize + 1) {
                    left = rot(left, right, null);
                    leftSize += rightSize;
                    right = null;
                    rightSize = 0;
                    forcing = left;
                } else {
                    assert(rightSize <= leftSize);
                    if (forcing != null) {
                        forcing = forcing.tail();
                    }
                }
            }

            return new Queue<A>(left, leftSize, right, rightSize, forcing);
        }

        public Queue<A> update(final int index, final A value) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException();
            }

            // TODO: we can do better

            final Queue<A> after = drop(index + 1);
            Node<A> left = new Node<A>(value, after._left);
            if (index > 0) {
                final Iterator<A> iter = iterator();
                Node<A> before = null;
                for (int i = 0; i < index; ++i) {
                    before = new Node<A>(iter.next(), before);
                }
                for (; before != null; before = before.tail()) {
                    left = new Node<A>(before.head(), left);
                }
            }
            return new Queue<A>(left, size() - after._rightSize, after._right, after._rightSize, after._forcing);
        }

        public Iterator<A> iterator() {
            return new QueueIterator<A>(_left, _right);
        }

        public Queue<A> prepend(final A v) {
            return new Queue<A>(new Node<A>(v, _left), _leftSize + 1, _right, _rightSize, _forcing);
        }

        public Queue<A> append(final A v) {
            return makeAndForce(_left, _leftSize, new Node<A>(v, _right), _rightSize + 1, _forcing);
        }

    }

}
