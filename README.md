<p align="center"><img src="logo.png" alt="datalevin logo" height="140"></img></p>
<h1 align="center">Datalevin</h1>
<p align="center"> 🧘 Simple, fast and versatile Datalog database for everyone 💽 </p>
<p align="center">
<a href="https://cljdoc.org/d/datalevin/datalevin"><img src="https://cljdoc.org/badge/datalevin/datalevin" alt="datalevin on cljdoc"></img></a>
<a href="https://clojars.org/datalevin"><img src="https://img.shields.io/clojars/v/datalevin.svg?color=success" alt="datalevin on clojars"></img></a>
<a href="https://github.com/juji-io/datalevin/actions"><img src="https://github.com/juji-io/datalevin/actions/workflows/release.binaries.yml/badge.svg?branch=0.6.19" alt="datalevin linux/macos build status"></img></a>
<a href="https://ci.appveyor.com/project/huahaiy/datalevin"><img src="https://ci.appveyor.com/api/projects/status/github/juji-io/datalevin?svg=true" alt="datalevin windows build status"></img></a>

</p>

## :hear_no_evil: What and why

> I love Datalog, why hasn't everyone used this already?

Datalevin is a simple durable Datalog database.

The rationale is to have a simple, fast and free Datalog query engine running on
durable storage.  It is our observation that many developers prefer the flavor
of Datalog popularized by [Datomic®](https://www.datomic.com) over any flavor of
SQL, once they get to use it. Perhaps it is because Datalog is more declarative
and composable than SQL, e.g. the automatic implicit joins seem to be its killer
feature.

Datomic® is an enterprise grade software, and its feature set may be an overkill
for some use cases. One thing that may confuse casual users is its [temporal
features](https://docs.datomic.com/cloud/whatis/data-model.html#time-model). To
keep things simple and familiar, Datalevin does not store transaction history,
and behaves the same way as most other databases: when data are deleted, they
are gone.

Datalevin started out as a port of
[Datascript](https://github.com/tonsky/datascript) in-memory Datalog database to
[Lightning Memory-Mapped Database
(LMDB)](https://en.wikipedia.org/wiki/Lightning_Memory-Mapped_Database). It
retains the library property of Datascript, and it is meant to be embedded in
applications to manage state. Because data is persistent on disk in Datalevin,
application state can survive application restarts, and data size can be larger
than memory.

Datalevin can also run in an event-driven networked [client/server](https://github.com/juji-io/datalevin/blob/master/doc/server.md) mode (default
port is 8898). The mode change is transparent. In the local mode, a data
directory path, e.g. `/data/mydb`, is needed for database location, whereas a
URI, e.g. `dtlv://myname:secret@myhost.in.cloud/mydb` is used in the client/server
mode. The same set of core functions work in both modes. In addition,
full-fledged role-based access control (RBAC) is provided on the server.

Datalevin relies on the robust ACID transactional database features of LMDB.
Designed for concurrent read intensive workloads, LMDB is used in many projects,
e.g.
[Cloudflare](https://blog.cloudflare.com/introducing-quicksilver-configuration-distribution-at-internet-scale/)
global configuration distribution. LMDB also [performs
well](http://www.lmdb.tech/bench/ondisk/) in writing large values (> 2KB).
Therefore, it is fine to store documents in Datalevin.

Datalevin uses a covering index and has no write-ahead log, so once the data are
written, they are indexed. There are no separate processes or threads for
indexing, compaction or doing any database maintenance work that compete with
your applications for resources. Since Datalog is simply a more ergonomic query
language than SQL, Datalevin can serve the role of an easier-to-use and
more lightweight relational database (RDBMS), e.g. where SQLite or Firebird is called for.

Independent from Datalog, Datalevin can be used as a fast key-value store for
[EDN](https://en.wikipedia.org/wiki/Extensible_Data_Notation) data, with support
for range queries, predicate filtering and more. The native EDN data capability
of Datalevin should be beneficial for Clojure programs. One can use this feature
in situations where something like Redis is called for, for instance.

In addition, Datalevin has a [built-in full-text search
engine](https://github.com/juji-io/datalevin/blob/master/doc/search.md) that has
[competitive](https://github.com/juji-io/datalevin/tree/master/search-bench)
search performance. It integrates nicely with other database features, and works in
all modes of Datalevin operation: embedded library, client/server, native
command line and as a [Babashka](https://github.com/babashka/babashka) Pod.

Our goal is to simplify data storage and access by supporting diverse use cases
and paradigms, because maximal flexibility is the core strength of a Datalog
store. Datalevin may not be the fastest or the most scalable solution for one
particular use case, but it would surely support the most number of them in a
coherent and elegant manner.

Using one data store for different use cases simplifies and reduces the cost of
software development, deployment and maintenance. Therefore, we plan to
implement necessary extensions to make Datalevin also a production rule engine,
a graph database, and a document database, since the storage and index structure
of Datalevin is already compatible with all of them.

Presentation:

* [2020 London Clojurians Meetup](https://youtu.be/-5SrIUK6k5g)

## :truck: Installation

Datalevin can be installed with different methods, depending on how you plan to use it.

### Clojure Library

The core of Datalevin is a Clojure library, simply add it to your project as a dependency
and start using it!

If you use [Leiningen](https://leiningen.org/) build tool, add this to the
`:dependencies` section of your `project.clj` file:

```Clojure
[datalevin "0.6.19"]
```

If you use [Clojure CLI](https://clojure.org/guides/deps_and_cli) and
`deps.edn`, declare the dependency like so:

```Clojure
{:deps {datalevin/datalevin {:mvn/version "0.6.19"}
        com.cognitect/transit-clj {:mvn/version "1.0.329"}}}
```

This JVM library supports Java 8 and above. For **JVM version newer than 11**, you may want to add the following JVM options:
```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```
Or you will get errors such as "Could not initialize class org.lmdbjava.ByteBufferProxy".


#### GraalVM Native Image

If your application depends on the Datalevin library and you want to compile your
application to a GraalVM native image, put `org.clojars.huahaiy/datalevin-native`
instead  (they have the same version number) in your `project.clj` or `deps.edn` file.

This is necessary because `datelevin-native` artifact contains GraalVM specific
code that should not appear in a regular JVM library. See also this
[note](https://github.com/juji-io/datalevin/tree/master/native#compiling-datalevin-dependency-to-native-image).

### Command Line Tool

A command line tool [`dtlv`](https://github.com/juji-io/datalevin/blob/master/doc/dtlv.md) is built to work with Datalevin databases in shell
scripting, doing work such as database backup/compaction, data import/export,
query/transaction execution, server administration, and so on. The same binary
can also run as a Datalevin server. This tool also includes a REPL with a Clojure
interpreter, in addition to support all the database functions.

Unlike many other database software (e.g. SQLite, Postgres, etc.) that introduces
a separate language for the command line, the same Clojure
code works in both Datalevin library and Datalevin command line tool.

A native Datalevin is built by compiling into [GraalVM native
image](https://www.graalvm.org/reference-manual/native-image/). In addition to
fast startup times, it should also have better index access speed, for the
native image version does not incur JNI overhead and uses a comparator written
in C, see [blog
post](https://yyhh.org/blog/2021/02/writing-c-code-in-javaclojure-graalvm-specific-programming/).

Here is how to get the Datalevin command line tool:

#### MacOS and Linux Package

Install using [homebrew](https://brew.sh/)

```console
brew install huahaiy/brew/datalevin
```

#### Windows Package

Install using [scoop](https://scoop.sh/)

```console
# Note: if you get an error you might need to change the execution policy (i.e. enable Powershell) with
# Set-ExecutionPolicy RemoteSigned -scope CurrentUser
Invoke-Expression (New-Object System.Net.WebClient).DownloadString('https://get.scoop.sh')

scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add extras
scoop install datalevin
```

#### Docker

```console
docker pull huahaiy/datalevin
```
See [README on Docker hub](https://hub.docker.com/r/huahaiy/datalevin) for usage.

#### Direct Download

Or download the executable binary from github:

* [Linux](https://github.com/juji-io/datalevin/releases/download/0.6.19/dtlv-0.6.19-ubuntu-latest-amd64.zip)
  on x86-64 (AMD64)
* [MacOS](https://github.com/juji-io/datalevin/releases/download/0.6.19/dtlv-0.6.19-macos-latest-amd64.zip)
  on x86-64 (AMD64)
* [Windows](https://github.com/juji-io/datalevin/releases/download/0.6.19/dtlv-0.6.19-windows-amd64.zip) on x86-64 (AMD64)

Unzip, put it on your path, and execute `dtlv help`:

```console
  Datalevin (version: 0.6.19)

Usage: dtlv [options] [command] [arguments]

Commands:
  copy  Copy a database, regardless of whether it is now in use
  drop  Drop or clear a database
  dump  Dump the content of a database to standard output
  exec  Execute database transactions or queries
  help  Show help messages
  load  Load data from standard input into a database
  repl  Enter an interactive shell
  serv  Run as a server
  stat  Display statistics of database

Options:
  -a, --all                            Include all of the sub-databases
  -c, --compact                        Compact while copying
  -d, --dir PATH                       Path to the database directory
  -D, --delete                         Delete the sub-database, not just empty it
  -f, --file PATH                      Path to the specified file
  -g, --datalog                        Dump/load as a Datalog database
  -h, --help                           Show usage
  -l, --list                           List the names of sub-databases instead of the content
  -p, --port PORT  8898                Listening port number
  -r, --root ROOT  /var/lib/datalevin  Server root data directory
  -v, --verbose                        Show verbose server debug log
  -V, --version                        Show Datalevin version and exit

Type 'dtlv help <command>' to read about a specific command.

```


Starting `dtlv` without any arguments goes into the console:

```console
  Datalevin (version: 0.6.19)

  Type (help) to see available functions. Some Clojure core functions are also available.
  Type (exit) to exit.

user> (help)

In addition to some Clojure core functions, the following functions are available:

In namespace datalevin.core

add                   add-doc               clear                 clear-dbi
close                 close-db              close-kv              closed-kv?
closed?               commit                conn-from-datoms      conn-from-db
conn?                 copy                  create-conn           datom
datom-a               datom-e               datom-v               datom?
datoms                db                    db?                   dir
doc-count             doc-indexed?          doc-refs              drop-dbi
empty-db              entid                 entity                entity-db
entries               get-conn              get-first             get-range
get-some              get-value             hexify-string         index-range
init-db               k                     list-dbis             listen!
new-search-engine     open-dbi              open-kv               opts
pull                  pull-many             put-buffer            q
range-count           range-filter          range-filter-count    read-buffer
remove-doc            reset-conn!           resolve-tempid        retract
rseek-datoms          schema                search                search-index-writer
seek-datoms           stat                  tempid                touch
transact              transact!             transact-async        transact-kv
unhexify-string       unlisten!             update-schema         v
visit                 with-conn             write

In namespace datalevin.interpret

definterfn            exec-code             inter-fn              inter-fn-from-reader
inter-fn?             load-edn

In namespace datalevin.client

assign-role           close-database        create-database       create-role
create-user           disconnect-client     drop-database         drop-role
drop-user             grant-permission      list-databases        list-databases-in-use
list-role-permissions list-roles            list-user-permissions list-user-roles
list-users            new-client            open-database         query-system
reset-password        revoke-permission     show-clients          withdraw-role

Can call function without namespace: (<function name> <arguments>)

Type (doc <function name>) to read documentation of the function

user>

```

You may want to launch `dtlv` in `rlwrap` to get a better REPL experience.

#### Uberjar

A JVM
[uberjar](https://github.com/juji-io/datalevin/releases/download/0.6.19/datalevin-0.6.19-standalone.jar)
is downloadable to use as the command line tool. It is useful when one wants to
run a Datalevin server and needs the efficiency of JVM's JIT, as GraalVM native
image is AOT and not as efficient as JVM for long running programs, or when a
pre-built native version is not available for your platform. For example,
assuming your Java is newer than version 11:

```console
java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -jar datalevin-0.6.19-standalone.jar
```
This will start the Datalevin REPL.

```console
java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -jar datalevin-0.6.19-standalone.jar serv -r /tmp/test-server
```
Will run the Datalevin server on default port 8898, with root data path at
`/tmp/test-server`.

### Babashka Pod

The `dtlv` executable can also run as a
[Babashka](https://github.com/babashka/babashka)
[pod](https://github.com/babashka/pods). It is also possible to download
Datalevin directly from [pod
registry](https://github.com/babashka/pod-registry) within a Babashka script
(not all versions are registered):

```
#!/usr/bin/env bb

(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.6.8")

```

For pod usage, an extra macro `defpodfn` is provided to define a custom function
that can be used in a query, e.g.:

```console
$ rlwrap bb
Babashka v0.7.6 REPL.
Use :repl/quit or :repl/exit to quit the REPL.
Clojure rocks, Bash reaches.

user=> (require '[babashka.pods :as pods])
nil
user=> (pods/load-pod "dtlv")
#:pod{:id "pod.huahaiy.datalevin"}
user=>  (require '[pod.huahaiy.datalevin :as d])
nil
user=> (d/defpodfn custom-fn [n] (str "hello " n))
#:pod.huahaiy.datalevin{:inter-fn custom-fn}
user=> (d/q '[:find ?greeting :where [(custom-fn "world") ?greeting]])
#{["hello world"]}
user=> (def conn (d/get-conn "/tmp/bb-test"))
#'user/conn
user=> (d/transact! conn [{:name "hello"}])
{:datoms-transacted 1}
user=> (d/q '[:find ?n :where [_ :name ?n]] (d/db conn))
#{["hello"]}
user=> (d/close conn)
nil
user=>
```
The example above uses `dtlv` binary in the PATH.

## :tada: Usage

Datalevin is aimed to be a versatile database.

### Use as a Datalog store

In addition to [our API doc](https://cljdoc.org/d/datalevin/datalevin),
since Datalevin has almost the same Datalog API as Datascript, which in turn has
almost the same API as Datomic®, please consult the abundant tutorials, guides
and learning sites available online to learn about the usage of Datomic® flavor
of Datalog.

Here is a simple code example using Datalevin:

```clojure
(require '[datalevin.core :as d])

;; Define an optional schema.
;; Note that pre-defined schema is optional, as Datalevin does schema-on-write.
;; However, attributes requiring special handling need to be defined in schema,
;; e.g. many cardinality, uniqueness constraint, reference type, and so on.
(def schema {:aka  {:db/cardinality :db.cardinality/many}
             ;; :db/valueType is optional, if unspecified, the attribute will be
             ;; treated as EDN blobs, and may not be optimal for range queries
             :name {:db/valueType :db.type/string
                    :db/unique    :db.unique/identity}})

;; Create DB on disk and connect to it, assume write permission to create given dir
(def conn (d/get-conn "/tmp/datalevin/mydb" schema))
;; or if you have a Datalevin server running on myhost with default port 8898
;; (def conn (d/get-conn "dtlv://myname:mypasswd@myhost/mydb" schema))

;; Transact some data
;; Notice that :nation is not defined in schema, so it will be treated as an EDN blob
(d/transact! conn
             [{:name "Frege", :db/id -1, :nation "France", :aka ["foo" "fred"]}
              {:name "Peirce", :db/id -2, :nation "france"}
              {:name "De Morgan", :db/id -3, :nation "English"}])

;; Query the data
(d/q '[:find ?nation
       :in $ ?alias
       :where
       [?e :aka ?alias]
       [?e :nation ?nation]]
     (d/db conn)
     "fred")
;; => #{["France"]}

;; Retract the name attribute of an entity
(d/transact! conn [[:db/retract 1 :name "Frege"]])

;; Pull the entity, now the name is gone
(d/q '[:find (pull ?e [*])
       :in $ ?alias
       :where
       [?e :aka ?alias]]
     (d/db conn)
     "fred")
;; => ([{:db/id 1, :aka ["foo" "fred"], :nation "France"}])

;; Close DB connection
(d/close conn)
```

### Use as a key-value store

Datalevin packages the underlying LMDB database as a convenient key-value store
for EDN data.

```clojure
(require '[datalevin.core :as d])
(import '[java.util Date])

;; Open a key value DB on disk and get the DB handle
(def db (d/open-kv "/tmp/datalevin/mykvdb"))
;; or if you have a Datalevin server running on myhost with default port 8898
;; (def db (d/open-kv "dtlv://myname:mypasswd@myhost/mykvdb" schema))

;; Define some table (called "dbi", or sub-databases in LMDB) names
(def misc-table "misc-test-table")
(def date-table "date-test-table")

;; Open the tables
(d/open-dbi db misc-table)
(d/open-dbi db date-table)

;; Transact some data, a transaction can put data into multiple tables
;; Optionally, data type can be specified to help with range query
(d/transact-kv
  db
  [[:put misc-table :datalevin "Hello, world!"]
   [:put misc-table 42 {:saying "So Long, and thanks for all the fish"
                       :source "The Hitchhiker's Guide to the Galaxy"}]
   [:put date-table #inst "1991-12-25" "USSR broke apart" :instant]
   [:put date-table #inst "1989-11-09" "The fall of the Berlin Wall" :instant]])

;; Get the value with the key
(d/get-value db misc-table :datalevin)
;; => "Hello, world!"
(d/get-value db misc-table 42)
;; => {:saying "So Long, and thanks for all the fish",
;;     :source "The Hitchhiker's Guide to the Galaxy"}

;; Delete some data
(d/transact-kv db [[:del misc-table 42]])

;; Now it's gone
(d/get-value db misc-table 42)
;; => nil

;; Range query, from unix epoch time to now
(d/get-range db date-table [:closed (Date. 0) (Date.)] :instant)
;; => [[#inst "1989-11-09T00:00:00.000-00:00" "The fall of the Berlin Wall"]
;;     [#inst "1991-12-25T00:00:00.000-00:00" "USSR broke apart"]]

;; Close key value db
(d/close-kv db)
```
### Entities with staged transactions (Datalog store)

In other Datalog DBs (Datomic®, DataScript, and Datahike) `d/entity` returns a type
that errors on associative updates. This makes sense because Entity represents
a snapshot state of a DB Entity and `d/transact` demarcates transactions.
However, this API leads to a cumbersome developer experience, especially
for the removal of fields where vectors of `[:db/retract <eid> <attr> <optional eid>]`
must be used in transactions because `nil` values are not allowed.

Datalevin ships with a special Entity type that allows for associative updates
while remaining immutable until expanded during transaction time (`d/transact`).
This type works the same in either local or remote mode.

Below are some examples. Look for the `:<STAGED>` keyword in the printed entities

```clojure
(require '[datalevin.core :as d])

(def db
  (-> (d/empty-db nil {:user/handle  #:db {:valueType :db.type/string
                                           :unique    :db.unique/identity}
                       :user/friends #:db{:valueType   :db.type/ref
                                          :cardinality :db.cardinality/many}})
      (d/db-with [{:user/handle  "ava"
                   :user/friends [{:user/handle "fred"}
                                  {:user/handle "jane"}]}])))

(def ava (d/entity db [:user/handle "ava"]))

(d/touch ava)
; => {:user/handle ava, :user/friends #{#:db{:id 3} #:db{:id 2}}, :db/id 1}
(assoc ava :user/age 42)
; => {:user/handle  ava
;     :user/friends #{#:db{:id 3} #:db{:id 2}},
;     :db/id        1,
;     :<STAGED>     #:user{:age [{:op :assoc} 42]}} <-- staged transaction!

(d/touch (d/entity db [:user/handle "ava"]))
; => {:user/handle ava, :user/friends #{#:db{:id 3} #:db{:id 2}}, :db/id 1}
; immutable! – db entity remains unchanged

(def db2 (d/db-with db [(assoc ava :user/age 42)]))

(def ava-with-age (d/entity db [:user/handle "ava"]))

(d/touch ava-with-age)
;=> {:user/handle "ava",
;    :user/friends #{#:db{:id 3} #:db{:id 2}},
;    :user/age 42, <-- age was transacted!
;    :db/id 1}

(def db3
  (d/db-with db2 [(-> ava-with-age
                      (update :user/age inc)
                      (d/add :user/friends {:user/handle "eve"}))]))

;; eve exists
(d/touch (d/entity db3 [:user/handle "eve"]))
;; => {:user/handle "eve", :db/id 4}

; eve is a friend of ada
(d/touch (d/entity db3 [:user/handle "ava"]))
;=> {:user/handle "ava",
;    :user/friends #{#:db{:id 4} <-- that's eve!
;                    #:db{:id 3}
;                    #:db{:id 2}},
;    :user/age 43,
;    :db/id 1}

; Oh no! That was a short-lived friendship.
; eve and ava got into an argument 😔

(def db4
  (d/db-with
    db3
    [(d/retract (d/entity db3 [:user/handle "ava"]) :user/friends [{:db/id 4}])]))

(d/touch (d/entity db4 [:user/handle "ava"]))
;=> {:user/handle "ava",
;    :user/friends #{#:db{:id 3} #:db{:id 2}}, ; <-- eve is not a friend anymore
;    :user/age 43,
;    :db/id 1}
```

For more examples have a look at the [tests](https://github.com/juji-io/datalevin/blob/master/test/datalevin/test/entity.cljc#L42-L109).

This Entity API is new and can be improved. For example, it does not currently resolve
lookup refs like `[:user/handle "eve"]`. If you'd like to help, feel free to reach out to
@den1k.

## :green_book: Documentation

Please refer to the [API
documentation](https://cljdoc.org/d/datalevin/datalevin) for more details.
You may also consult online materials for Datascript or Datomic®, as the Datalog API is
similar.

## :birthday: Upgrade

Please read
[this](https://github.com/juji-io/datalevin/blob/master/doc/upgrade.md) for
information regarding upgrading your existing database from older versions.

## :earth_americas: Roadmap

These are the tentative goals that we try to reach as soon as we can. We may
adjust the priorities based on feedback.

* 0.4.0 ~~Native image and native command line tool.~~ [Done 2021/02/27]
* 0.5.0 ~~Native networked server mode with access control.~~ [Done 2021/09/06]
* 0.6.0 ~~As a search engine: full-text search across database.~~ [Done 2022/03/10]
* 0.7.0 Data type feature parity with Datomic: bigint, bigdec, tuples, composite tuples, etc.
* 0.8.0 A new Datalog query engine with improved performance.
* 0.9.0 As a production rule engine: implement iterative rules application and
  truth maintenance.
* 1.0.0 First major release with good documentation.
* 1.1.0 Transaction log storage and access API.
* 1.2.0 Read-only replicas for server.
* 1.3.0 Distributed mode with raft based replication.
* 2.0.0 Second major release with a book.
* 2.2.0 Option to store data in compressed form.
* 2.3.0 Arbitrary data as attribute.
* 2.4.0 Fully automatic schema migration on write.
* 3.0.0 As a document store: automatic indexing.
* 4.0.0 As a graph database: implementing [loom](https://github.com/aysylu/loom) graph protocols.


## :rocket: Status

Both Datascript and LMDB are mature and stable libraries. Building on top of
them, Datalevin is extensively tested with property-based testing. It is also used
in production at [Juji](https://juji.io).

Running the [benchmark suite adopted from
Datascript](https://github.com/juji-io/datalevin/tree/master/bench) on a Ubuntu
Linux server with an Intel i7 3.6GHz CPU and a 1TB SSD drive, here is how it
looks.

<p align="center">
<img src="bench/datalevin-bench-query-01-05-2021.png" alt="query benchmark" height="300"></img>
<img src="bench/datalevin-bench-write-01-05-2021.png" alt="write benchmark" height="300"></img>
</p>

In all benchmarked queries, Datalevin is faster than Datascript. Considering
that we are comparing a disk store with a memory store, this result may be
counter-intuitive. One reason is that Datalevin caches more
aggressively, whereas Datascript chose not to do so (e.g. see [this
issue](https://github.com/tonsky/datascript/issues/6)). Before we introduced
caching in version 0.2.8, Datalevin was only faster than Datascript for single
clause queries due to the highly efficient reads of LMDB. With caching enabled,
Datalevin is now faster across the board. In addition, we will soon move to a
more efficient query implementation.

Writes are slower than Datascript, as expected, as Datalevin is writing to disk
while Datascript is in memory. The bulk write speed is good, writing 100K datoms
to disk in less than 0.5 seconds; the same data can also be transacted with all
the integrity checks as a whole in less than 2 seconds. Transacting one datom or
five datoms at a time, it takes more or less than that time.

In short, Datalevin is quite capable for small or medium projects right now.
Large scale projects can be supported when distributed mode is implemented.

## :floppy_disk: Differences from Datascript

Datascript is developed by [Nikita Prokopov](https://tonsky.me/) that "is built
totally from scratch and is not related by any means to" Datomic®. Although
a port, Datalevin differs from Datascript in more significant ways
than just the difference in data durability and running mode:

* As mentioned, Datalevin is not an immutable database, and there is no
  "database as a value" feature.  Since history is not kept, transaction ids are
  not stored.

* Datoms in a transaction are committed together as a batch, rather than being
  saved by `with-datom` one at a time.

* Respects `:db/valueType`. Currently, most [Datomic® value
  types](https://docs.datomic.com/on-prem/schema.html#value-types) are
  supported, except uri. Values of the attributes that
  are not defined in the schema or have unspecified types are treated as
  [EDN](https://en.wikipedia.org/wiki/Extensible_Data_Notation) blobs, and are
  de/serialized with [nippy](https://github.com/ptaoussanis/nippy).

* The attribute and value leading index (AVE) is enabled for all datoms, so
  there is no need to specify `:db/index`, similar to Datomic® Cloud. Does not
  have AEV index, in order to save storage and improve write speed.

* Has two novel types of indices: Encla and Links, which enables a query planner
  that minimizes query cost.

* Attributes are stored in indices as integer ids, thus attributes in index
  access are returned in attribute creation order, not in lexicographic order
  (i.e. do not expect `:b` to come after `:a`). This is the same as Datomic®.

* Transaction functions should be defined with `intern-fn`, for
  function serialization requires special care in order to support GraalVM.

* Has no features that are applicable only for in-memory DBs, such as DB as an
  immutable data structure, DB pretty print, etc.

This project would not have started without the existence of Datascript, we will
continue submitting pull requests to Datascript with our improvements where they
are applicable to Datascript.

## :baby: Limitations

* Attribute names have a length limitation: an attribute name cannot be more
  than 511 bytes long, due to LMDB key size limit.

* Because keys are compared bitwise, for range queries to work as expected on an
  attribute, its `:db/valueType` should be specified.

* Floating point `NaN` cannot be stored.

* Cannot store big integer beyond the range of `[-2^1015, 2^1015-1]`, the
  unscaled value of big decimal has the same limit.

* The maximum individual value size is 2GB. Limited by the maximum size of
  off-heap byte buffer that can be allocated in JVM.

* The total data size of a Datalevin database has the same limit as LMDB's, e.g.
  128TB on a modern 64-bit machine that implements 48-bit address spaces.

* Currently supports Clojure on JVM 8 or the above, but adding support for other
  Clojure-hosting runtime is possible, since bindings for LMDB
  exist in almost all major languages and available on most platforms.

## :shopping: Alternatives

If you are interested in using the dialect of Datalog pioneered by Datomic®, here are your current options:

* If you need time travel and features backed by the authors of Clojure, you should use [Datomic®](https://www.datomic.com).

* If you need an in-memory store that has almost the same API as Datomic®, [Datascript](https://github.com/tonsky/datascript) is for you.

* If you need a graph database, you may try [Asami](https://github.com/threatgrid/asami).

* If you need features such as bi-temporal graph queries, you may try [XTDB](https://github.com/xtdb/xtdb).

* If you need a durable store with some storage choices, you may try [Datahike](https://github.com/replikativ/datahike).

* There was also [Eva](https://github.com/Workiva/eva/), a distributed store, but it is no longer in active development.

* If you need a simple, fast and versatile durable store with a battle tested backend, give [Datalevin](https://github.com/juji-io/datalevin) a try.

## :arrows_clockwise: Contact

We appreciate and welcome your contribution or suggestion. Please feel free to
file issues or pull requests.

If commercial support is needed for Datalevin, talk to us.

You can talk to us in the `#datalevin` channel on [Clojurians Slack](http://clojurians.net/).

## License

Copyright © 2020-2022 [Juji, Inc.](https://juji.io).

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).
