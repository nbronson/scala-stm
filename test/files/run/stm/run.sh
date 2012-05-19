#!/bin/sh

SCALA_VERSION=2.9.2
STM_VERSION=0.6-SNAPSHOT

for t in "$@"; do
  TEST=`basename $t .scala`
  rm -f $TEST.raw $TEST.pass $TEST.fail
  
  JAR=../../../../target/scala-$SCALA_VERSION/scala-stm_${SCALA_VERSION}-$STM_VERSION.jar
  if [ ! -f $JAR ]; then
    echo "$JAR not found" 1>&2
    exit 1
  fi
  TMP=classes.$$

  EXTRA=""
  if [ "x$TEST" = "xjava_api" ]; then
    EXTRA=JavaAPITests.java
  fi
  
  /bin/echo -n $TEST .
  mkdir $TMP
  rm -f $TEST.raw
  if [ "x$TEST" = "xjava_api" ]; then
    J=$JAR
    for f in `which scala | sed 's|/bin/scala||'`/lib/*.jar; do
      J="${f}:$J"
    done
    javac -d $TMP -classpath $J JavaAPITests.java >> $TEST.raw 2>&1
  fi
  scalac -d $TMP -cp ${JAR}:$TMP $TEST.scala >> $TEST.raw 2>&1
  /bin/echo -n .
  if scala -cp ${TMP}:$JAR Test >> $TEST.raw 2>&1; then
    mv $TEST.raw $TEST.pass
    echo . pass
  else
    mv $TEST.raw $TEST.fail
    echo . FAIL
  fi
  rm -rf $TMP
done
