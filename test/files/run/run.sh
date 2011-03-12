#!/bin/sh

SCALA_VERSION=2.8.1
STM_VERSION=0.3-SNAPSHOT

for t in "$@"; do
  TEST=`basename $t .scala`
  rm -f $TEST.raw $TEST.pass $TEST.fail
  
  JAR=../../../../target/scala_$SCALA_VERSION/scala-stm_${SCALA_VERSION}-$STM_VERSION.jar
  TMP=classes.$$
  
  /bin/echo -n $TEST .
  mkdir $TMP
  scalac -d $TMP -cp $JAR $TEST.scala > $TEST.raw 2>&1
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
