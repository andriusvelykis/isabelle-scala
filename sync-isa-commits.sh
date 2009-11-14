#!/bin/sh
#
# Usage: ./sync-isa-commits.sh ISABELLE_REPO_DIR [FROM_REV] [TO_REV]
#
# Arguments:
#   ISABELLE_REPO_DIR  the directory of Isabelle Mercurial repository
#   FROM_REV           revision to start from (current one by default)
#   TO_REV             revision to end sync (tip by default)
#
# Synchronizes commits about Isabelle Scala from Isabelle Mercurial
# repository to isabelle-scala GIT repository. Uses the sync-isa-file.sh
# script to sync required files
#
# Author: Andrius Velykis
#

ISABELLE_REPO="$1"

[ -z "$ISABELLE_REPO" ] && echo "Missing Isabelle repository directory" && exit 2

ISABELLE_SCALA_DIR=`pwd`

AUTHOR_EXPECTED="wenzelm"
AUTHOR_NAME="Makarius Wenzel"
# e-mail in reverse to avoid spambots crawling the repository
AUTHOR_EMAIL_REV="ten.siteks@suirakam"

FILES_MATCH='jedit\|src/Pure[^ ]*.scala\|build-jars'


# ensure the repo is updated to the master
git checkout master

# Go to Isabelle folder
cd $ISABELLE_REPO

FROM_REV="$2"
TO_REV="$3"

# get revision of the current copy if unset
[ -z "$FROM_REV" ] && FROM_REV=`hg id --num`

# get revision of the tip
[ -z "$TO_REV" ] && TO_REV=`hg tip --template '{rev}'`

[ -z "$FROM_REV" ] && echo "Missing from revision" && exit 2
[ -z "$TO_REV" ] && echo "Missing to revision" && exit 2

# start from the next revision
r=$(($FROM_REV + 1))

echo "Starting sync at revision $r."

while [ $r -le "$TO_REV" ]
do

  echo "Analysing revision $r..."

  cd $ISABELLE_REPO

  MESSAGE=`hg log --rev $r --template '{desc}'`
  DATE=`hg log --rev $r --template '{date|rfc822date}'`
  AUTHOR=`hg log --rev $r --template '{author}'`
  FILES=`hg log --rev $r --template '{files}'`
  HASH=`hg log --rev $r --template '{node}'`
  
#  echo "$MESSAGE"
#  echo "$DATE"
#  echo "$AUTHOR"
#  echo "$FILES"

  # stop on first jEdit encounter
  JEDIT_MATCH=`echo "$FILES" | grep -i "jedit"`
  [ -n "$JEDIT_MATCH" ] && echo "Found jEdit - stopping" && exit 2;

  MATCH=`echo "$FILES" | grep -i "$FILES_MATCH"`

#  echo "$MATCH"
  if [ -n "$MATCH" ];
  then
  
    [ "$AUTHOR" != "$AUTHOR_EXPECTED" ] && echo "Unexpected author found: $AUTHOR. Expected: $AUTHOR_EXPECTED" && exit 2;
    
    echo "Picking revision $r by $AUTHOR."
    
    NEWMESSAGE=`echo "$MESSAGE\n\nIsabelle-hg: http://isabelle.in.tum.de/repos/isabelle@$r $HASH"`
#    echo "$NEWMESSAGE"

    # update to the revision
    hg update --clean --rev "$r"
    
    cd $ISABELLE_SCALA_DIR
    
    # launch file copy script
    ./sync-isa-files.sh "$ISABELLE_REPO"
    RETVAL=$?
    [ $RETVAL -ne 0 ] && echo "Copying failed" && exit 2;
    
    # add-remove items
    git add --all
    
    AUTHOR_EMAIL=`echo "$AUTHOR_EMAIL_REV" | rev`;
    
    git commit --message="$NEWMESSAGE" --date="$DATE" --author="$AUTHOR_NAME <$AUTHOR_EMAIL>";
    
    # Try to retain the author's date as committer date
    LAST_COMMIT_DATE=`git log -1 --format="%ct" HEAD^1`;
    NEW_AUTHOR_DATE=`git log -1 --format="%at" HEAD`;
    
    # To preserve monotonicity of commit dates, if author is before the last committer,
    # then just increase the committer date by a short period of time
    if [ "$NEW_AUTHOR_DATE" -gt "$LAST_COMMIT_DATE" ];
    then
      NEW_COMMIT_DATE="$DATE"
    else
      NEW_COMMIT_DATE=`expr $LAST_COMMIT_DATE + 10`
    fi
    
    export NEW_COMMIT_DATE
    
    # rewrite the committer date of last commit
    git filter-branch -f --env-filter \
    'export GIT_COMMITTER_DATE="$NEW_COMMIT_DATE"' HEAD^1..HEAD
    
    
#  else
#    echo "Skipping $AUTHOR";
  fi
  
  r=$(( $r + 1 ))
done

# update to the final revision (so remove 1)
cd $ISABELLE_REPO
r=$(( $r - 1 ))
hg update --clean --rev "$r"

