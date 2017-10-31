---
layout: default
title: Getting the source
---

ScalaSTM is hosted at
[github.com/nbronson/scala-stm](http://github.com/nbronson/scala-stm).
If you system has `git`, the best way to get the source code is to clone
this repository.

{% highlight bash %}
git clone https://github.com/nbronson/scala-stm.git
{% endhighlight %}

If `git` is not available you can download a
[tar.gz](https://github.com/nbronson/scala-stm/tarball/master) or
[zip](https://github.com/nbronson/scala-stm/zipball/master) source
bundle. These are automatically generated from the current state of the
master branch.

ScalaSTM is released under the new BSD [license](license.html).

Building from source {#manual}
--------------------

Building and testing is done with
[sbt](http://code.google.com/p/simple-build-tool/).

1.  Download the test-time dependency (`scalatest`)
    {% highlight bash %}
    sbt update
    {% endhighlight %}
2.  Compile everything and run the tests
    {% highlight java %}
    sbt test
    {% endhighlight %}
3.  Build a JAR file (it will end up in a subdirectory of `target`)
    {% highlight bash %}
    sbt package
    {% endhighlight %}

