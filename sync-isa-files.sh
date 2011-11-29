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

ISABELLE_PIDE_DIR=$ISABELLE_SCALA_DIR/isabelle.pide
ISABELLE_PIDE_SRC=$ISABELLE_PIDE_DIR/src/isabelle

ISABELLE_PURE_DIR=$ISABELLE_SCALA_DIR/isabelle.pure
ISABELLE_PURE_SRC=$ISABELLE_PURE_DIR/src/isabelle

ISABELLE_REPO_JEDIT_DIR=$ISABELLE_REPO/src/Tools/jEdit
ISABELLE_REPO_PURE_DIR=$ISABELLE_REPO/src/Pure

# Go to Isabelle-jEdit folder
[ ! -d "$ISABELLE_REPO_JEDIT_DIR" ] && echo "Missing Isabelle repo jEdit folder: $ISABELLE_REPO_JEDIT_DIR" && exit 2
cd $ISABELLE_REPO_JEDIT_DIR

# Clean jEdit source folder
rm -rf $ISABELLE_JEDIT_SRC && mkdir $ISABELLE_JEDIT_SRC

# Copy jEdit source contents
cp -rf src/ $ISABELLE_JEDIT_SRC/

# Copy the rest of jEdit folder
rsync --recursive --relative --times --perms --delete --exclude "src" --exclude ".classpath" --exclude ".project" --exclude ".settings" . $ISABELLE_JEDIT_DIR

# Go to Isabelle-Pure folder
[ ! -d "$ISABELLE_REPO_PURE_DIR" ] && echo "Missing Isabelle repo Pure folder: $ISABELLE_REPO_PURE_DIR" && exit 2
cd $ISABELLE_REPO_PURE_DIR

# Clean PIDE & Pure source folders
rm -rf $ISABELLE_PIDE_SRC && mkdir $ISABELLE_PIDE_SRC
rm -rf $ISABELLE_PURE_SRC && mkdir $ISABELLE_PURE_SRC

# Copy all *.scala files to respective PIDE and Pure projects
find . -type f -iname '*.scala' -exec sh -c 'if grep "Module:.*PIDE" {} >/dev/null
  then 
    cp -rf {} $0/
  else 
    cp -rf {} $1/
  fi' $ISABELLE_PIDE_SRC $ISABELLE_PURE_SRC \;

# Copy the build-jars script
cp -rf build-jars $ISABELLE_PURE_DIR/build-jars;
