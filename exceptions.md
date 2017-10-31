---
layout: default
title: Exceptions
---

What happens when an atomic block throws an exception? There is a debate
in the STM community about whether the transaction should be rolled back
or committed. ScalaSTM uses a hybrid approach that tries to do the right
thing.

Exception --&gt; rollback + rethrow {#default}
-----------------------------------

If an atomic block throws an exception, ScalaSTM rolls it back and then
rethrows the exception. The atomic block will be left rolled back when
the exception is rethrown. For exceptions that represent real errors
this is a good default behavior, because it prevents corruption of any
shared data structures.

Control-flow exception --&gt; commit + rethrow {#controlflow}
----------------------------------------------

Sometimes exceptions represent a non-local control transfer, rather than
an unexpected error. In this case, the transaction should be committed.
ScalaSTM tests each exception that escapes an atomic block to determine
which behavior is appropriate (look at the ScalaDoc for
`TxnExecutor.isControlFlow` for more). By default all exceptions that
extend `scala.util.control.ControlThrowable` are considered to be
control flow.

Exceptions and nesting {#nesting}
----------------------

The previous rules about exception handling apply to nested
transactions. This means that a nested transaction might be rolled back
while the outer transaction is committed. For example, after the
following code runs `last` will hold the value `"outer"`:

{% highlight scala %}
val last = Ref("none")
atomic { implicit txn =>
  last() = "outer"
  try {
    atomic { implicit txn =>
      last() = "inner"
      throw new RuntimeException
    }
  } catch {
    case _: RuntimeException =>
  }
}
{% endhighlight %}

To make nesting very cheap, ScalaSTM tries to flatten all of the nesting
levels together into a single top-level transaction. If an inner
transaction throws an exception then there isn't enough information to
perform the partial rollback, so ScalaSTM restarts the entire
transaction in a mode that does exact nesting. This optimization is
called subsumption.
