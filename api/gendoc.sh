#!/bin/bash

# Takes a git branch as an argument, and updates that branch's ScalaDoc
# subdirectory.  Grabs the version number from the project/build.properties in
# the specified branch.

BRANCH="$1"

set -e   # exit on any command failure
set -x   # print all of the commands before running them

TMP=/tmp/.gendoc.$$
trap "rm -rf $TMP" EXIT

mkdir $TMP
( cd $TMP ; \
  git clone https://github.com/nbronson/scala-stm.git ;
  cd scala-stm ; \
  git checkout "$BRANCH" ; \
  sbt doc )

VERSION=`awk -F= '$1=="project.version" {print $2}' $TMP/scala-stm/project/build.properties`
if [ -x "$VERSION" ]; then
  mv "$VERSION" .orig."$VERSION".$$
fi

mv `find $TMP/scala-stm/target -name api` $VERSION
