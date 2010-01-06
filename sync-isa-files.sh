#!/bin/sh
#
# Usage: ./sync-isa-files.sh ISABELLE_REPO_DIR
#
# Arguments:
#   ISABELLE_REPO_DIR the directory of Isabelle repository
#
# Synchronizes Scala files from Isabelle repository to isabelle-scala
# projects, which would be used from within Eclipse Scala-IDE.
#
# Author: Andrius Velykis
#

ISABELLE_REPO="$1"

[ -z "$ISABELLE_REPO" ] && echo "Missing Isabelle repository directory" && exit 2

ISABELLE_SCALA_DIR=`pwd`

ISABELLE_JEDIT_DIR=$ISABELLE_SCALA_DIR/isabelle.jedit
ISABELLE_JEDIT_SRC=$ISABELLE_JEDIT_DIR/src/isabelle/jedit

ISABELLE_PURE_DIR=$ISABELLE_SCALA_DIR/isabelle.pure
ISABELLE_PURE_SRC=$ISABELLE_PURE_DIR/src/isabelle

ISABELLE_REPO_JEDIT_DIR=$ISABELLE_REPO/lib/jedit/plugin
ISABELLE_REPO_PURE_DIR=$ISABELLE_REPO/src/Pure

# Go to Isabelle-jEdit folder
#[ ! -d "$ISABELLE_REPO_JEDIT_DIR" ] && echo "Missing Isabelle repo jEdit folder: $ISABELLE_REPO_JEDIT_DIR" && exit 2
#cd $ISABELLE_REPO_JEDIT_DIR

# Clean jEdit source folder
#rm -rf $ISABELLE_JEDIT_SRC && mkdir $ISABELLE_JEDIT_SRC

# Copy jEdit source contents
#find . -type f -iname '*.scala' -exec cp -rf \{\} "$ISABELLE_JEDIT_SRC" \;

# Copy the rest of jEdit folder
#rsync --recursive --relative --times --perms --exclude "*.scala" --exclude "src" --exclude ".classpath" --exclude ".project" --exclude ".settings" . $ISABELLE_JEDIT_DIR

# Go to Isabelle-Pure folder
[ ! -d "$ISABELLE_REPO_PURE_DIR" ] && echo "Missing Isabelle repo Pure folder: $ISABELLE_REPO_PURE_DIR" && exit 2
cd $ISABELLE_REPO_PURE_DIR

# Clean Pure source folder
rm -rf $ISABELLE_PURE_SRC && mkdir $ISABELLE_PURE_SRC

# Copy all *.scala files to Pure project
find . -type f -iname '*.scala' -exec cp -rf \{\} "$ISABELLE_PURE_SRC" \;

# Copy the build-jars script
cp -rf mk-jars $ISABELLE_PURE_DIR/mk-jars;
