---
layout: default
title: Frequently Asked Questions
---

**Q.** Can ScalaSTM run my existing code in parallel with no changes?

**A.** No. You will need to replace shared variables with `Ref`-s.

**Q.** Why do you have both `Ref` and `Ref.View`?

**A.** `Ref` operations can only be called inside an `atomic` block.
`Ref.View` allows access to the underlying mutable cell both inside or
outside a transaction. We separated them so that you have to take an
explicit step to bypass the safety of `atomic`. The implicit `InTxn`
instance passed to `Ref` methods also provides a small performance
boost.

**Q.** Can I use `Ref.View` all the time if I want?

**A.** If your program is correct using `Ref` then it will still be
correct after replacing `Ref` with `Ref.View`, but there will be no
compile-time or run-time checking that accesses are inside an atomic
block.

**Q.** Doesn't having both `Ref`-s and `Ref.View`-s result in a lot of
wasted memory?

**A.** Underneath, a single instance can implement both `Ref` and
`Ref.View`. Also, if it is known when creating a `Ref` that it holds a
primitive value, the returned instance can be specialized to the
primitive type to avoid long-term boxing. In the reference
implementation a `Ref[Int]` is 8 bytes larger than a boxed `Int`.

**Q.** `Ref.View` (from `Ref.single`) nests in a transaction, but does a
transaction nest in `Ref.View.transform`? In other words, is it safe to
call `transform(_ => atomic { ... })` on a `Ref.View`?

**A.** No, to keep overhead low `Ref.View.transform` doesn't create a
full transaction if one isn't already active. Also, remember that the
function passed to `transform` might be executed multiple times.

**Q.** I want to make a big array of primitive values, won't
`Array[Ref[Int]]` be too wasteful then?

**A.** Yes. To address this we provide `TArray[A]`, which acts like an
`Array[Ref[A]]` but allows for more efficient storage.

**Q.** What's the difference between the ScalaSTM library, the ScalaSTM
API and the ScalaSTM reference implementation?

**A.** The ScalaSTM API is the classes that appear in user code. They
are in `scala.concurrent.stm._`, and they don't change when plugging in
a new implementation. The reference implementation is the one used by
default. The ScalaSTM library is the JAR file, which includes both the
API and the reference implementation.

**Q.** If two separately-compiled components of the system use ScalaSTM,
can they call each other from inside an atomic block?

**A.** Yes. The ScalaSTM implementation is selected at runtime (using
the system property `scala.stm.impl`), so all components using ScalaSTM
will be connected to the same implementation.

**Q.** How can you say "ScalaSTM guarantees *X*" when the underlying
implementation is pluggable?

**A.** What we really mean is: "Any underlying implementation is
required to guarantee the property *X*, unless it has been explicitly
disabled by an implementation-specific customization mechanism."

**Q.** How is ScalaSTM related to Nathan Bronson's
[CCSTM](http://ppl.stanford.edu/ccstm)?

**A.** The ScalaSTM API borrows heavily from CCSTM's public interface,
which was in turn inspired by Daniel Spiewak's STM. The reference
implementation included in ScalaSTM is an improved version of CCSTM's
algorithm.

**Q.** How do you handle I/O and native method calls from inside
transactions?

**A.** We include a complete set of life-cycle handlers that can be used
to perform manual cleanup, or to participate in a two-phase commit with
other transactional resources. There is no automatic support.

**Q.** Didn't Joe Duffy's
[retrospective](http://www.bluebytesoftware.com/blog/2010/01/03/ABriefRetrospectiveOnTransactionalMemory.aspx)
on STM.NET say handlers were not sufficient?

**A.** ScalaSTM does not have the goal of running arbitrary existing
code, which is where most of their problems arose.

**Q.** Strong atomicity was too slow for STM.NET even though they
modified the JIT directly, how can you provide it as just a library?

**A.** Unlike STMs that add `atomic` as a keyword in the language,
ScalaSTM only provides transactional behavior for a subset of the heap
(the values stored inside `Ref`-s). The vast majority of loads and
stores bypass ScalaSTM, keeping the overhead low. Another way to think
about this is that efficient strong atomicity requires the help of the
type system, which we encode in the difference between `A` and `Ref[A]`.

**Q.** Does ScalaSTM provide publication and privatization safety?

**A.** Yes.

**Q.** Does ScalaSTM allow write skew?

**A.** No, although pluggable implementations may allow it to be
selectively enabled as an optimization.

**Q.** Will ScalaSTM work with Scala on non-JVM platforms?

**A.** The ScalaSTM reference implementation uses classes from
`java.util.concurrent.atomic` in a few key places internally, but it
should be straightforward to replace these with the CLR functionality
exposed by `System::Threading::Interlocked`. Because ScalaSTM does not
do bytecode rewriting there will be no need for MSIL rewriting.

**Q.** Is there a version of the STAMP benchmark available for ScalaSTM?

**A.** No. We have implemented an adapter for the STMBench7 benchmark,
though. See the [results](benchmark.html).
