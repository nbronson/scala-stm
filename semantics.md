---
layout: default
title: Semantics
---

ScalaSTM provides very strong guarantees (strong atomicity and
isolation), but only for accesses to `Ref` and the provided
transactional collections. This is different from STMs that retrofit
transactional behavior onto all variables of an existing language.

Write skew {#writeskew}
----------

Write skew occurs when a transaction's reads are validated at a
different point in time from where its writes are applied.
MVCC (multi-version concurrency control) algorithms allows write skew,
because it performs reads against a snapshot taken at the beginning of a
transaction while using locks to atomically apply the writes at the end.

One of the nice features of true atomicity is that it allows local
correctness reasoning for each transaction. A system that tolerates
write skews allows a greater fraction of transactions to commit, which
is a good thing, but it makes correctness a global property. Some
pluggable ScalaSTM implementations may allow relaxed consistency to be
selectively enabled, but write skew is not allowed by default.

Strong atomicity {#atomicity}
----------------

At its most basic, a software transactional memory is a way of isolating
a group of memory accesses and verifying that those accesses are
equivalent to some serial execution. The STM barriers that perform the
transactional reads and writes include code that blocks or rolls back
any accesses that violate atomicity or isolation. If non-transactional
code bypasses the barriers and accesses an STM-managed memory location
directly, however, the barriers can no longer detect all violations.

There are three potential responses to the weak isolation between direct
memory accesses and concurrent transactions:

-   The runtime can provide strong atomicity and isolation by
    redirecting all memory accesses to barriers, even non-transactional
    accesses. While there has been some research in using dynamic
    recompilation to reduce the performance penalty of strong isolation,
    these require either deep integration with the VM's JIT [^1]\
    or a substantial warmup period [^2].


-   The language can declare that a conflicting concurrent access from
    both inside and outside a transaction is an unchecked user error.
    This doesn't sound too onerous, but the optimistic nature of
    transactions means that failed speculations must also be considered:
    inconsistent transactions may execute conflicting accesses from an
    impossible branch, or they may execute conflicting accesses after
    they have become doomed. Restrictions on commit order can prevent
    some of the most surprising behaviors [^3], but the resulting
    systems still require whole-program reasoning to guarantee
    correctness. The privatization and publication problems refer to
    isolation failure for specific idioms.


-   The type system can prevent direct access to any memory location
    that might be touched transactionally [^4]. This can take the form
    of extending the type and access rules on normal mutable memory
    locations, or of encapsulating transactionally-managed data as
    private variables of some sort of cell, as in Haskell [^5] and
    Clojure [^6]. We refer to the latter approach as a reference-based
    STM.

Scala favors safety and compile-time checking of program correctness, so
the authors are of the opinion that it is only natural to employ types
to avoid the problems of weak isolation. A deep extension to Scala's
type system to directly encode transactionality would be possible, but
we can get type checking for much less effort by using the
reference-based approach. ScalaSTM provides strong atomicity by
encapsulating all transactionally-managed memory locations inside
references.

Opacity
-------

A subtle issue with STM is that, unless special care is taken, only
committed transactions are guaranteed to be consistent. Speculative
transactions may observe an inconsistent state and only subsequently
detect that they should roll back. These 'zombies' can produce
surprising behavior by taking impossible branches or performing
transactional accesses to the wrong object. This problem is greatly
magnified in a reference based STM, because the STM cannot provide a
sandbox that isolates all actions taken by the zombie. The read of a
single impossible value may produce an infinite loop, so a transparent
STM must either prevent inconsistent reads or instrument back edges to
periodically revalidate the transaction. Only the first option is
available to an STM implemented as a library.

The TL2 [^7] and LSA [^8] algorithms use a global time-stamp to
efficiently validate a transaction after each read, guaranteeing
consistency for all intermediate states. This correctness property is
formalized as *opacity* [^9].

The reference implementation included with ScalaSTM is based on SwissTM
[^10], which adds eager detection of write-write conflicts to TL2's
validation algorithm. Additional implementations should also guarantee
opacity, unless it is explicitly disabled using `TxnExecutor.withHint`
or `TxnExecutor.withConfig`.

Irrevocable actions {#irrevocable}
-------------------

One of the side effects of ScalaSTM's alternate syntax for transactional
barriers is that it avoids creating the impression that the STM can
magically parallelize all existing sequential code, or that atomic
blocks are always a better replacement for locks. This avoids
scalability problems stemming from incidental dependencies, and avoids
the semantic problems of irrevocable actions.

The semantic problems with hiding rollback and retry come from actions
that the STM cannot isolate or undo, such as I/O or calls to external
libraries. ScalaSTM does not try to automatically handle irrevocable
actions. Instead, it allows the user to register handlers to perform
manual cleanup or two-phase commit. Handler registration is accomplished
via methods of `object Txn`.

[^1]: F. T. Schneider, V. Menon, T. Shpeisman, and A.-R. Adl-Tabatabai.
    Dynamic Optimization for Efficient Strong Atomicity. In *OOPSLA '08:
    Proceedings of the 23rd ACM SIGPLAN conference on Object-Oriented
    Programming Systems, Languages, and Applications*, New York, NY,
    USA, October 2008. ACM.

[^2]: N. G. Bronson, C. Kozyrakis, and K. Olukotun. Feedback-Directed
    Barrier Optimization in a Strongly Isolated STM. In *POPL '09:
    Proceedings of the 36th Annual ACM SIGPLAN-SIGACT Symposium on
    Principles of Programming Languages*, pages 213--225, New York, NY,
    USA, 2009. ACM.

[^3]: V. Menon, S. Balensieger, T. Shpeisman, A.-R. Adl-Tabatabai, R. L.
    Hudson, B. Saha, and A. Welc. Practical Weak-Atomicity Semantics for
    Java STM. In *SPAA '08: Proceedings of the 20th ACM Symposium on
    Parallel Algorithms and Architectures*, 2008.

[^4]: K. F. Moore and D. Grossman. High-Level Small-Step Operational
    Semantics for transactions. In *POPL '08: Proceedings of the 35th
    Annual ACM SIGPLAN-SIGACT Symposium on Principles of Programming
    Languages*, pages 51--62, New York, NY, USA, 2008. ACM.

[^5]: T. Harris, S. Marlow, S. Peyton-Jones, and M. Herlihy. Composable
    Memory Transactions. In *PPoPP '05: Proceedings of the tenth ACM
    SIGPLAN Symposium on Principles and Practice of Parallel
    Programming*, pages 48--60, New York, NY, USA, 2005. ACM.

[^6]: R. Hickey. The Clojure Programming Language. In *Proceedings of
    the 2008 Symposium on Dynamic Languages*. ACM New York, NY, USA,
    2008.

[^7]: D. Dice, O. Shalev, and N. Shavit. Transactional Locking II. In
    *DISC '06: Proceedings of the 20th International Symposium on
    Distributed Computing*, March 2006.

[^8]: T. Riegel, P. Felber, and C. Fetzer. A Lazy Snapshot Algorithm
    with Eager Validation. In *Disc '06: Proceedings of the 20th
    International Symposium on Distributed Computing*, pages 284--298,
    2006.

[^9]: R. Guerraoui and M. Kapalka. On the Correctness of Transactional
    Memory. In *PPoPP '08: Proceedings of the 13th ACM SIGPLAN Symposium
    on Principles and Practice of Parallel Programming*, pages 175--184,
    New York, NY, USA, 2008. ACM.

[^10]: A. Dragojevic, R. Guerraoui, and M. Kapalka. Stretching
    Transactional Memory. In *PLDI '09: Proceedings of the 2009 ACM
    SIGPLAN Conference on Programming Language Design and
    Implementation*, pages 155--165, New York, NY, USA, 2009. ACM.
