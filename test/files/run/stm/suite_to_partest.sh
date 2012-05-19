#!/bin/bash

BASE=`git rev-parse --show-toplevel`

for f in `find $BASE -name '*Suite.scala'`; do
  p=`echo $f | sed -e 's/API/Api/' -e 's/Suite.scala//' -e 's/^.*\///' -e 's/[A-Z]/_\0/g' | tr '[A-Z]' '[a-z]' | sed 's/^_//'`.scala
  echo $f $p
  awk '
{ drop = 0; prefix = "" }

/^  / { prefix = "  " }
/^package/ { drop = 1 }
/org.scalatest/ { print "import scala.concurrent.stm._\nimport scala.concurrent.stm.skel._\nimport scala.concurrent.stm.japi._\nimport scala.concurrent.stm.impl._" ; drop = 1 }

/^class/ { drop = 1 ;
           print "object Test {\n\n  def test(name: String)(block: => Unit) {\n    println(\"running retry \" + name)\n    block\n  }\n\n  def intercept[X](block: => Unit)(implicit xm: ClassManifest[X]) {\n    try {\n      block\n      assert(false, \"expected \" + xm.erasure)\n    } catch {\n      case x if (xm.erasure.isAssignableFrom(x.getClass)) => // okay\n    }\n  }\n\n  def main(args: Array[String]) {" }

/^}/ { print "  }" }

drop==0 { print prefix $0 }' $f | \
    sed -e 's/===/==/g' \
        -e 's/test(\(.*\), Slow)/if ("slow" == "enabled") test(\1)/' \
        -e 's/txn[.]rootLevel/NestingLevel.root(txn)/' \
        -e 's/txn[.]status/Txn.status(txn)/' \
        -e 's/private //' \
        -e 's/ fail(/ throw new Error(/' \
        -e 's/txn.afterCommit \(.*\)/Txn.afterCommit(\1)(txn)/' > $BASE/test/files/run/stm/$p
done
