---
layout: default
title: Library-Based Software Transactional Memory for Scala
short_title: Welcome
---

Welcome from the creators of Scala STM. We've built a lightweight
software transactional memory for Scala, inspired by the STMs in Haskell
and Clojure while taking advantage of Scala's power and performance.

ScalaSTM is a single JAR with no dependencies, and includes

-   An API that supports multiple STM implementations
-   A reference implementation based on CCSTM
-   Scalable concurrent sets and maps (with fast snapshots) that can be
    used inside or outside transactions

ScalaSTM provides a mutable cell called a `Ref`. If you build a shared
data structure using immutable objects and `Ref`-s, then you can access
it from multiple threads or actors. No `synchronized`, no deadlocks or
race conditions, and good scalability. Included are concurrent sets and
maps, and we also have an easier and safer replacement for `wait` and
`notifyAll`.

[Learn more...](intro.html)

News
----

-   *2017 Nov 4* -- ScalaSTM version 0.8 published for 2.13-M2


-   (major) *2016 Nov 17* -- ScalaSTM version 0.8
    [released](releases.html), including
    -   added 2.12 build
    -   small `TxnLocal` improvements
    -   correctness fix for `TArray[Long]` and `AtomicArray.ofLong`


-   (major) *2014 Apr 17* -- ScalaSTM version 0.7 published for 2.11


-   *2014 Apr 7* -- ScalaSTM version 0.7 published for 2.11.0-RC4


-   *2014 Mar 20* -- ScalaSTM version 0.7 published for 2.11.0-RC3


-   *2014 Mar 7* -- ScalaSTM version 0.7 published for 2.11.0-RC1


-   *2014 Feb 4* -- ScalaSTM version 0.7 published for 2.11.0-M8


-   *2014 Jan 12* -- ScalaSTM version 0.7 published for 2.11.0-M7


-   *2013 Sep 15* -- ScalaSTM version 0.7 published for 2.11.0-M5


-   *2013 Jul 28* -- ScalaSTM version 0.7 published for 2.11.0-M4


-   *2013 May 22* -- ScalaSTM version 0.7 published for 2.11.0-M3


-   *2013 Jan 12* -- ScalaSTM version 0.7 published for 2.11.0-M1


-   (major) *2012 Dec 21* -- ScalaSTM version 0.7
    [released](releases.html), including
    -   debugger support via
        [TxnDebuggable](api/0.7/scala/concurrent/stm/TxnDebuggable.html)
    -   cooperation with Scala 2.10's thread pools
    -   additional convenience methods on `Ref`, `Ref.View`, and
        `TxnLocal`


-   *2012 Oct 16* -- [0.7-SNAPSHOT](snapshots.html): Improved support
    for interactive debuggers via `scala.concurrent.stm.TxnDebuggable`


-   *2012 Oct 16* -- Maven artifacts are now published under the
    "org.scala-stm" group id (publishing to "org.scala-tools" will
    continue for current versions), and are now synced automatically
    with the Maven central repo


-   (major) *2012 Jul 22* -- ScalaSTM version 0.6
    [released](releases.html), including
    -   enhancements to the Java convenience layer
    -   uses of `scala.actors.threadpool.TimeUnit` replaced by
        `java.util.concurrent.TimeUnit`


-   (major) *2012 Feb 2* -- ScalaSTM version 0.5
    [released](releases.html), including
    -   Java convenience layer `scala.concurrent.stm.japi.STM`


-   (major) *2011 Nov 9* -- ScalaSTM version 0.4
    [released](releases.html), including
    -   `CommitBarrier`, support for group commit
    -   STMBench7 benchmark support
    -   Better implementation selection


-   *2011 Sep 5* -- ScalaSTM 0.3 and 0.4-SNAPSHOT for Scala 2.9.1
    [released](releases.html)


-   *2011 Jul 8* -- [0.4-SNAPSHOT](snapshots.html): STMBench7 support.
    See [Benchmarking](benchmark.html)


-   (major) *2011 May 12* -- ScalaSTM version 0.3 for 2.9.0
    [released](releases.html)


-   *2011 May 8* -- ScalaSTM version 0.3 for 2.9.0.RC4
    [released](releases.html)


-   *2011 May 6* -- ScalaSTM version 0.3 for 2.9.0.RC3
    [released](releases.html)


-   (major) *2011 Mar 26* -- ScalaSTM version 0.3
    [released](releases.html), a pure-Scala software TM library.
    Included
    -   Support for Scala 2.9.0.RC1
    -   Timeouts for `retry`


-   *2011 Feb 4* -- [0.3-SNAPHOT](snapshots.html): Bug fixes.


-   *2011 Jan 15* -- [0.3-SNAPHOT](snapshots.html): Timeouts for
    `retry`. See [Waiting : Timeouts](modular_blocking.html#timeouts)


-   *2011 Jan 2* -- [0.3-SNAPHOT](snapshots.html): Bug fixes.


-   (major) *2010 Dec 27* -- ScalaSTM version 0.2
    [released](releases.html), a library-based software transactional
    memory (STM) for Scala. Included
    -   Performance improvements and bug fixes
    -   Better `TSet`, `TMap` and `TxnLocal`
    -   Transaction statistics


-   *2010 Dec 16* -- [0.2-SNAPSHOT](snapshots.html): Bug fix for
    while-committing handlers
    ([\#3](https://github.com/nbronson/scala-stm/issues/closed#issue/3)).
    `TxnLocal` can now be read and written from while-preparing and
    while-committing handlers.


-   *2010 Dec 8* -- [0.2-SNAPSHOT](snapshots.html): Substantial
    performance improvements, especially for nested atomic blocks.
    `TMap.View` and `TSet.View` are integrated with Scala collection
    class hierarchy.


-   (major) *2010 Dec 6* -- ScalaSTM version 0.1 released, a
    library-based software transactional memory (STM) for Scala. [Get
    it...](releases.html)

