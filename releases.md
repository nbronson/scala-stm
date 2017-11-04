---
layout: default
title: Releases
---

The latest release of ScalaSTM is version 0.7. To get it you can

-   follow [sbt instructions](#sbt) or [maven2 instructions](#maven)
-   download a prebuilt JAR
    -   for 2.13.0-M2:
        [scala-stm\_2.13.0-M2-0.8.jar](https://oss.sonatype.org/content/repositories/releases/org/scala-stm/scala-stm_2.13.0-M2/0.8/scala-stm_2.13.0-M2-0.8.jar)
    -   for all 2.12 releases:
        [scala-stm\_2.12-0.8.jar](https://oss.sonatype.org/content/repositories/releases/org/scala-stm/scala-stm_2.12/0.8/scala-stm_2.12-0.8.jar)
    -   for all 2.11 releases:
        [scala-stm\_2.11-0.8.jar](https://oss.sonatype.org/content/repositories/releases/org/scala-stm/scala-stm_2.11/0.8/scala-stm_2.11-0.8.jar)

Release notes {#notes}
-------------

Changes between 0.7 and 0.8:

-   correctness fix for TArray\[Long\] and AtomicArray.ofLong.
-   small improvement to TxnLocal interface.
-   add 2.12 build and remove 2.10 build.
-   add deprecated message about incomplete deadlock detection for
    CommitBarrier.

Changes between 0.6 and 0.7:

-   better support for interactive debuggers, via
    [TxnDebuggable](api/0.7/scala/concurrent/stm/TxnDebuggable.html) and
    `atomic.unrecorded`. IntelliJ IDEA and Eclipse can now watch `Ref`
    values inside a transaction without affecting the read or write
    sets.
-   ScalaSTM cooperates with 2.10's `scala.concurrent.BlockingContext`.
-   added `transformAndExtract` to `Ref`, `Ref.View`, and `TxnLocal`,
    which allows an arbitrary value to be returned from the
    transformation function.
-   added `transformAndGet` and `getAndTransform` to `Ref` and
    `TxnLocal`, previously these were only defined for `Ref.View`.

Changes between 0.5 and 0.6:

-   `retry` and `retryFor` added to the Java compatibility interface.
-   uses of `scala.actor.threadpool.TimeUnit` in the interface replaced
    with `java.util.concurrent.TimeUnit` to avoid making ScalaSTM depend
    on the separate `scala-actors.jar` in Scala 2.10.

Changes between 0.4 and 0.5

-   `scala.concurrent.stm.japi.STM` added, which makes it much cleaner
    to access ScalaSTM functionality from Java.

Changes between 0.3 and 0.4

-   `CommitBarrier` added, which allows multiple atomic blocks (each on
    its own thread) to commit together.
-   Small performance improvements.
-   STMBench7 benchmark support added.
-   Automatic selection of STMImpl in most cases.

Changes between 0.2 and 0.3

-   Support for Scala 2.9.0.
-   Timeouts for `retry`. See [Waiting :
    Timeouts](modular_blocking.html#timeouts).
-   Bug fixes and contention management improvements
    -   [\#11](https://github.com/nbronson/scala-stm/issues/closed#issue/11)
        -- fix for two-`Ref` form of `compareAndSet`;
    -   [\#12](https://github.com/nbronson/scala-stm/issues/closed#issue/12)
        -- rare `StackOverflowException` under heavy contention and true
        nesting;
    -   [\#13](https://github.com/nbronson/scala-stm/issues/closed#issue/13)
        -- contention management improvements;
    -   [\#14](https://github.com/nbronson/scala-stm/issues/closed#issue/14)
        -- fix for external decision support;
    -   [\#15](https://github.com/nbronson/scala-stm/issues/closed#issue/15)
        -- NPE if a post-decision handler threw an exception;
    -   [\#18](https://github.com/nbronson/scala-stm/issues/closed#issue/18)
        -- atomicity can be broken by `TMap` copy-on-write mechanism;
        and
    -   [\#19](https://github.com/nbronson/scala-stm/issues/closed#issue/19)
        -- potential deadlock between pessimistic readers.

Changes between 0.1 and 0.2

-   Substantial performance improvements, especially for nested atomic
    blocks. Nested atomic blocks without `orAtomic` are optimistically
    subsumed (run directly in their parent transaction), then retried
    without subsumption if partial rollback is needed.
-   `TSet.View` and `TMap.View` are integrated into the Scala collection
    class hierarchy, with factory companion objects and `Builder` and
    `CanBuildFrom` instances.
-   A fix for `whileCommitting` handlers (issue
    [\#3](https://github.com/nbronson/scala-stm/issues/closed#issue/3)).
-   `TxnLocal` can now be read and written from while-preparing and
    while-committing handlers. Combining `TxnLocal` and life-cycle
    handlers is now more concise.
-   Transaction statistics can be enabled for the default algorithm with
    the VM argument `-Dccstm.stats=1` (for more information see the
    ScalaDoc for `scala.concurrent.stm.ccstm.CCSTM`).

SBT
---

Tell `sbt` about a dependency on ScalaSTM by adding a library dependency
to your `build.sbt` file (or a Scala build file)

{% highlight scala %}
libraryDependencies += ("org.scala-stm" %% "scala-stm" % "0.8")
{% endhighlight %}

`sbt update` will then download the correct `scala-stm` JAR and use it
for building.

Maven2 {#maven}
------

Separate artifacts are published for each version of Scala, so the
desired Scala version (such as 2.12) must be included in the
`artifactId` for Maven (`sbt` automatically handles this). The ScalaSTM
dependency for your `pom.xml` is

{% highlight xml %}
<dependencies>
  <dependency>
    <groupId>org.scala-stm</groupId>
    <artifactId>scala-stm_2.12</artifactId>
    <version>0.8</version>
  </dependency>
</dependencies>
{% endhighlight %}

The deployment tests inside the ScalaSTM source include a complete
[pom.xml](https://github.com/nbronson/scala-stm/blob/master/dep_tests/maven/pom.xml)
