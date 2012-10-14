#!/bin/bash

# Takes a git branch as an argument, and updates that branch's ScalaDoc
# subdirectory.  Second argument is the version of Scala under which to
# generate the docs.  Version is taken from project/build.properties in
# the specified branch.

BRANCH="$1"
SBT_SCALA_VER=""
if [ -n "$2" ]; then
  SBT_SCALA_VER="++$2"
fi

set -e   # exit on any command failure
set -x   # print all of the commands before running them

TMP=/tmp/.gendoc.$$
trap "rm -rf $TMP" EXIT

mkdir $TMP
( cd $TMP ; \
  git clone https://github.com/nbronson/scala-stm.git ;
  cd scala-stm ; \
  git checkout "$BRANCH" ; \
  sbt $SBT_SCALA_VER doc )

VERSION=`awk -F'"' '/^version/ {print $2}' $TMP/scala-stm/build.sbt`
if [ -x "$VERSION" ]; then
  mv "$VERSION" .orig."$VERSION".$$
fi

mv `find $TMP/scala-stm/target -name api` $VERSION
