# Isabelle/Scala 

A mirror of _Isabelle/Pure_ and _Isabelle/jEdit_ components from the official 
[Isabelle]( http://isabelle.in.tum.de/ ) repository.

## Structure

This repository mirrors the development of Isabelle/Scala components (Isabelle/Pure, Isabelle/PIDE,
Isabelle/jEdit) from the official repository at
[http://isabelle.in.tum.de/repos/isabelle/]( http://isabelle.in.tum.de/repos/isabelle/ ).

The code here is structured as Eclipse projects to be compatible with
[_Scala IDE_]( http://scala-ide.org/ ).
This is different from the official repository, which has the Scala source files located in 
different directories and brings them together using shell scripts.

## Synchronising with main repository

The repository is mirrored by importing changesets from the main Isabelle Mercurial repository and
relocating them to match the _Scala IDE_ structure. Only changesets concerning Isabelle/Scala code
are mirrored.

Import is performed by running the `sync-isa-commits.sh` script in the repository folder and 
indicating the location of a locally-cloned Isabelle Mercurial repository:

	./sync-isa-commits.sh ISABELLE_HG_REPO_DIR [FROM_REV] [TO_REV]

The `FROM_REV` and `TO_REV` parameters are optional revision ranges (number-based) to import.
If they are omitted, the default range is from current repository revision to the `tip`.

**Note:** commits regarding the restructuring of code are added with adjusted commit date to
preserve development history dates. The actual mirroring started in March 2012.
