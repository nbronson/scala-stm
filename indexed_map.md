---
layout: default
title: Indexed Map
---

ScalaSTM includes `TMap` and `TMap.View`, which are STM-integrated
concurrent maps. As for `Ref` and `Ref.View`, `TMap` operations check at
compile time that they are inside `atomic`, while `TMap.View` can be
used either inside or outside a transaction. There is also a
corresponding `TSet`.

As an example, let's use `TMap` to create an in-memory map that works
like a database table with indices (actually indices over a view). It
looks like this in the REPL

{% highlight scala %}
scala> case class User(id: Int, name: String, likes: Set[String])     
defined class User

scala> val m = new IndexedMap[Int, User]
m: IndexedMap[Int,User] = IndexedMap@19d2052b

scala> m.put(10, User(10, "alice", Set("scala", "climbing")))
res0: Option[User] = None

scala> val byName = m.addIndex { (id,u) => Some(u.name) }
byName: (String) => Map[Int,User] = <function1>

scala> val byLike = m.addIndex { (id,u) => u.likes }
byLike: (String) => Map[Int,User] = <function1>

scala> m.put(11, User(11, "bob", Set("scala", "skiing")))
res1: Option[User] = None

scala> byName("alice")
res2: Map[Int,User] = Map((10,User(10,alice,Set(scala, climbing))))

scala> byLike("scala").values map { _.name }
res3: Iterable[String] = List(alice, bob)
{% endhighlight %}

A high-level sketch {#sketch}
-------------------

The key of the `IndexedMap` works like the primary key of a database
table; it is used to uniquely identify entries to update or remove. An
initial cut at `IndexedMap` (without indices) can just pass operations
through to an underlying `TMap`. Since reads are only a single
transactional operation it is more concise and more efficient to use the
`TMap.View` returned from `TMap.single`.

{% highlight scala %}
import scala.concurrent.stm._

class IndexedMap[A, B] {
  private val contents = TMap.empty[A, B]

  // TODO def addIndex(view: ?): ?

  def get(key: A): Option[B] = contents.single.get(key)

  def put(key: A, value: B): Option[B] = atomic { implicit txn =>
    val prev = contents.put(key, value)
    // TODO: update indices
    prev
  }

  def remove(key: A): Option[B] = atomic { implicit txn =>
    val prev = contents.remove(key)
    // TODO: update indices
    prev
  }
}
{% endhighlight %}

Types for the view function and index {#types}
-------------------------------------

An index on the `IndexedMap` is defined by a function from an entry to a
derived value, the one that will be indexed. We can add flexibility with
little effort by letting the function return 0 or more derived values,
and then declaring that the entry matches if the desired value is one of
the derived ones. This means that the function that defines an index has
the type `(A, B) => Iterable[C]`.

An index should allow us to locate all of the entries with a particular
property, so the return type of an index lookup should be a collection
of entries. It is likely that the caller will want the actual values,
but to resolve duplicates the entries should also be identified by key.
This suggests that an index lookup should return something that can be
iterated to get pairs of `A` and `B`. `Map[A, B]` is an
`Iterable[(A, B)]`, and fits the bill nicely. Locating entries is the
entire purpose of an index, so an index can just be a function, rather
than a new public named type.

{% highlight scala %}
  def addIndex(view: ((A, B) => Iterable[C])): (C => Map[A, B]) = ...
{% endhighlight %}

Tracking and updating indices {#indexlist}
-----------------------------

We'll keep track of the indices with a `Ref` that holds an immutable
`List`. The STM's transactional types mix well with Scala's immutable
collections, because there is no problem sharing those between threads.
Note that we're careful to add all of the existing elements of the map
to a new index.

{% highlight scala %}
  private class Index[C](view: (A, B) => Iterable[C]) extends (C => Map[A, B]) {
    def += (kv: (A, B)) // TODO
    def -= (kv: (A, B)) // TODO
  }

  private val indices = Ref(List.empty[Index[_]])

  def addIndex[C](view: (A, B) => Iterable[C]): (C => Map[A, B]) = {
    atomic { implicit txn =>
      val index = new Index(view)
      indices() = index :: indices()
      contents foreach { index += _ }
      index
    }
  }
{% endhighlight %}

To simplify the index implementation, `put` will separate updates into
an index removal and an index insert. (It is straightforward to make a
more efficient implementation would optimize the case that a call to
`put` did not change an indexed property.)

{% highlight scala %}
  def put(key: A, value: B): Option[B] = atomic { implicit txn =>
    val prev = contents.put(key, value)
    for (p <- prev; i <- indices()) i -= (key -> p)
    for (i <- indices()) i += (key -> value)
    prev
  }

  def remove(key: A): Option[B] = atomic { implicit txn =>
    val prev = contents.remove(key)
    for (p <- prev; i <- indices()) i -= (key -> p)
    prev
  }
{% endhighlight %}

Index internals {#impl}
---------------

Now all that's left is the actual index implementation, which relies on
another `TMap`. This map maintains the exact mapping from property to
entries that is required to implement `Index.apply`. There's a bit of
care required to handle the case when there are new derived properties
or when all of the entries for a property are removed.

{% highlight scala %}
  private class Index[C](view: (A, B) => Iterable[C]) extends (C => Map[A, B]) {
    val mapping = TMap.empty[C, Map[A, B]]

    def apply(derived: C) = mapping.single.getOrElse(derived, Map.empty[A, B])

    def += (kv: (A, B))(implicit txn: InTxn) {
      for (c <- view(kv._1, kv._2))
        mapping(c) = apply(c) + kv
    }

    def -= (kv: (A, B))(implicit txn: InTxn) {
      for (c <- view(kv._1, kv._2)) {
        val after = mapping(c) - kv._1
        if (after.isEmpty)
          mapping -= c
        else
          mapping(c) = after
      }
    }
  }
{% endhighlight %}

The source {#source}
----------

The code for this example is part of the ScalaSTM source on github:
[IndexedMap.scala](https://github.com/nbronson/scala-stm/blob/master/src/test/scala/scala/concurrent/stm/examples/IndexedMap.scala).
