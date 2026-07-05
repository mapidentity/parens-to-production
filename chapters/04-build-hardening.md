# Strict Compilation: Catching Reflection and Boxed Math from Day One

Most Clojure projects add performance checks late -- after mysterious slowdowns in production, after profiling reveals a hot path doing reflective method calls. By then the warnings number in the hundreds and fixing them is a grind.

There is a better way. If you wire up strict compilation from the very first commit, you catch reflection warnings and boxed math warnings the moment they appear. One warning at a time is easy to fix. Three hundred is a project.

The build-hardening setup is three tools: `tools.build` with fail-on-warnings, zprint for consistent formatting, and clj-kondo for static analysis. Together they make the build refuse to produce an artifact with performance problems baked in, and keep the codebase formatted and linted with minimal effort.

You could enforce these checks elsewhere. Leiningen has long flipped `*warn-on-reflection*` from `project.clj`, and many teams park linting in a CI job that fails the pull request rather than the local build. Both work, and both also let a warning sit in the tree for hours -- through a push, a review cycle, a coffee -- before anyone is told. We wire the gate into `tools.build` itself, so the artifact simply will not build with a reflection or boxed-math warning in it, and the feedback lands at the moment of compilation on the machine that introduced the problem. The cost is a build that is stricter than some contributors will expect on their first commit; given the alternative is three hundred warnings discovered in a profiler, that is the trade we want.

## The `:build` alias

Everything starts in `deps.edn`. You only need one extra alias:

```clojure
:build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.7"}}
        :ns-default build}
```

That is it. The `:ns-default build` tells `clojure -T:build` to look for functions in a `build.clj` file at the project root. No build framework, no plugin ecosystem, just a Clojure file you can read and understand.

## The build namespace

Here is the build-hardening portion of `build.clj` -- the strict-compile gate and the uberjar task. The same file later grows a second half (content-hashing, SRI, the `assets`/`verify-assets` tasks, with two extra requires: `clojure.java.io` and `clojure.java.shell`) in [the asset pipeline chapter](24-asset-pipeline.md):

```clojure
(ns build
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b]))

(def lib
  "The Maven coordinates of the artifact this build produces."
  'com.myapp/myapp)

(def version
  "The version of the artifact this build produces."
  "0.1.0")

(def class-dir
  "Directory the AOT compiler writes .class files to. Also the root of the uberjar."
  "target/classes")

(def uber-file
  "The uberjar file the `uber` task produces. Run: `java -jar target/myapp.jar`."
  "target/myapp.jar")

(def basis
  "The build basis: the set of source paths, resource paths, and deps.edn files."
  (delay (b/create-basis {:project "deps.edn"})))

(defn clean
  "Delete the build output tree (target/). Run: clojure -T:build clean."
  [_]
  (b/delete {:path "target"}))

(defn- fail-on-warnings!
  "Scan stderr for reflection/boxed-math warnings from our code and throw."
  [s]
  (let [hits (->> (str/split-lines s)
                  (filter #(and (or (str/includes? % "Reflection warning,")
                                    (str/includes? % "Boxed math warning,"))
                                (str/includes? % "myapp/")))
                  (take 50)
                  (vec))]
    (when (seq hits)
      (throw (ex-info "Performance warnings detected — add type hints to fix." {:warnings hits})))))

(defn compile-strict
  "AOT-compile src with *warn-on-reflection* and *unchecked-math* :warn-on-boxed.
  Then fail if any warnings from our code were emitted. compile-clj runs in a
  subprocess, so we capture stderr via :err :capture and scan it."
  [_]
  (let [{:keys [err]} (b/compile-clj
                        {:basis @basis
                         :src-dirs ["src"]
                         :class-dir class-dir
                         :err :capture
                         :bindings {#'*warn-on-reflection* true
                                    #'*unchecked-math* :warn-on-boxed}})]
    (when err
      (binding [*out* *err*]
        (print err)
        (flush))
      (fail-on-warnings! err))
    (println "compile-strict: OK")))

(defn uber
  "Build an uberjar with the AOT-compiled classes and resources. Run: clojure -T:build uber."
  [_]
  (clean nil)
  (b/copy-dir
    {:src-dirs ["src" "resources"]
     :target-dir class-dir})
  (compile-strict nil)
  (b/uber
    {:class-dir class-dir
     :uber-file uber-file
     :basis @basis
     :main 'myapp.core}))
```

The subsections below take that listing apart.

### Delayed basis

```clojure
(def basis
  (delay (b/create-basis {:project "deps.edn"})))
```

The basis (resolved dependency tree) is wrapped in a `delay` so it is only computed when actually needed. If you call `clojure -T:build clean`, there is no reason to resolve the entire classpath. Small thing, but it keeps the fast path fast.

### The two compiler flags

The heart of strict compilation is two bindings passed to `compile-clj`:

```clojure
:bindings {#'*warn-on-reflection* true
           #'*unchecked-math* :warn-on-boxed}
```

**`*warn-on-reflection*`** tells the Clojure compiler to emit a warning any time it cannot resolve a Java method or field call at compile time. Without a type hint, the compiler falls back to runtime reflection -- scanning the class hierarchy on every call. This is slow (orders of magnitude slower than a direct call) and completely avoidable with a type hint.

Concretely, a "type hint" is a `^Class` metadata tag that tells the compiler what type a value is, so it can compile a direct method call instead of a reflective lookup:

```clojure
;; Reflection warning: compiler does not know what `s` is, so the
;; `.length` call is resolved by scanning the class at runtime.
(defn char-count [s] (.length s))
;; => Reflection warning ... reference to field length can't be resolved.

;; Hinted: `^String` tells the compiler `s` is a String, so `.length`
;; compiles to a direct, warning-free call.
(defn char-count [^String s] (.length s))
```

The hint goes on the argument (or on a `let` binding, or as a `^Type` tag in front of any expression). That one annotation is the entire fix the build is asking for.

**`*unchecked-math* :warn-on-boxed`** warns when arithmetic compiles down to boxed math: the compiler cannot see primitive operands, so `+` falls back to its `Object` overload and the values travel as heap-allocated `Long` or `Double` wrappers instead of raw machine words. Note the direction of cause and effect. The operation does not box values that were primitive; the operands were never primitive to begin with, and the warning is telling you so. In hot loops this is death by a thousand cuts.

The warning looks like this, and the fix is again a hint, a primitive one this time:

```clojure
;; Boxed math warning: `a` and `b` are untyped, so `+` compiles to
;; Numbers.unchecked_add(Object, Object) -- math on wrapper objects.
(defn add [a b] (+ a b))
;; => Boxed math warning ... unchecked_add(java.lang.Object,java.lang.Object).

;; Hinted: primitive longs in, a primitive add, nothing boxed.
(defn add [^long a ^long b] (+ a b))
```

These two are the strictness bindings. The repo's `compile-clj` passes one more -- `#'test/*load-tests* false`, via a `[clojure.test :as test]` require the listing above does not carry -- which has nothing to do with strictness: it strips in-file `deftest` forms out of the compiled artifact so tests never ride into production. We reach for it when the testing strategy needs it, so it is introduced in [the testing chapter](10-unit-testing.md); it lives in the same `:bindings` map only because that is where `compile-clj` takes them.

The `:bindings` map has a limit worth naming: it switches the flags on in the build subprocess, and only there. During development the same code loads in the REPL with `*warn-on-reflection*` at its default `false`, and a reflective call sits silent until the next build. The day-one half of the promise is a per-namespace switch:

```clojure
(set! *warn-on-reflection* true)
```

One line, directly after the `ns` form. The repository carries it in every `src` namespace except three pure-data ones (the Datomic schema and the two i18n string tables, which contain no interop to warn about), and you will start seeing it at the top of the book's listings in [the live-reload chapter](06-live-reload.md).

Per-namespace is not a stylistic preference; it is how the var works. The compiler rebinds `*warn-on-reflection*` around each file it loads, so a `set!` reaches to the end of the file it appears in and no further -- one `set!` in a dev bootstrap file cannot turn checking on for the codebase, because the var has snapped back to `false` by the time the next file loads. That scoping cuts the other way too. Because the switch must live in the file, it travels with the code it protects and is visible in review, and a static check can catch its absence, which is exactly what the clj-kondo configuration later in this chapter does.

With the line in place, the REPL prints `Reflection warning, ...` the moment you load an unhinted interop call, and the build gate becomes what a gate should be: the backstop, not the first notice. (`*unchecked-math*` gets no such per-namespace line in this codebase; boxed math is caught at the build gate alone.)

### Why `compile-clj` needs `:err :capture`

Here is a subtlety: `b/compile-clj` runs compilation in a subprocess. The warnings go to stderr of that subprocess. By default they scroll past and disappear. The `:err :capture` option collects them into a string so we can inspect them programmatically.

### `fail-on-warnings!` -- the gate

```clojure
(defn- fail-on-warnings!
  "Scan stderr for reflection/boxed-math warnings from our code and throw."
  [s]
  (let [hits (->> (str/split-lines s)
                  (filter #(and (or (str/includes? % "Reflection warning,")
                                    (str/includes? % "Boxed math warning,"))
                                (str/includes? % "myapp/")))
                  (take 50)
                  (vec))]
    (when (seq hits)
      (throw (ex-info "Performance warnings detected — add type hints to fix." {:warnings hits})))))
```

This function scans the captured stderr line by line. It filters for lines that are both a warning (reflection or boxed math) *and* come from our own code -- the warning line names a source file under `myapp/`. That second filter is important -- third-party libraries will emit their own warnings and you cannot fix those. You only fail on warnings you can actually act on.

The `(take 50)` is a safety valve, and it bounds the warnings attached to the thrown `ex-info` -- not what you see, since the full captured stderr is already printed above. The `myapp/` filter has already excluded anything a dependency emits, so the only code that can flood that attached list is your own, and realistically that happens in one scenario: retrofitting this gate onto a codebase that never ran it. The console still shows every warning in full; the cap just keeps the failure's data payload from ballooning. On a project that has carried the gate from the first commit, the list never gets near it.

When any hits are found, it throws an `ex-info` with the warnings attached as data. The build fails. No jar is produced. You fix the type hints, run again, and move on.

### The uberjar pipeline

Look back at `uber` in the full listing above: clean, copy sources and resources, compile strictly, then package. The strict compilation is not an optional lint step -- it is part of the build pipeline. You cannot produce a jar without passing the check. This is the key design decision. Making it part of the artifact build means it can never be skipped or forgotten.

A second decision hides in the same pipeline: the compiled classes go *into* the jar. Strict compilation only requires that the compiler run. We could treat `compile-strict` as a pure check, discard `target/classes`, and ship a source-only jar that compiles itself at every boot. For a library that would be the only responsible choice, because AOT compilation is contagious: compiling a namespace compiles everything it loads. `target/classes` here ends up holding `.class` files for `aero`, `hiccup`, `reitit`, `ring`, and every other dependency namespace the application reaches, frozen at the versions on the build classpath. Baked into a published library, those frozen classes would shadow whatever versions a consumer resolves.

An application uberjar is the end of the dependency chain: the same frozen jars ship inside the artifact, so the baked classes can never meet a conflicting version, and the contagion has nowhere to spread. What remains is the benefit (`java -jar` starts the precompiled `myapp.core` named in the manifest, with no compile pause at every deploy and restart) and one cost we accept: `uber` copies `src` in alongside the classes, so the artifact carries both sources and compiled classes and is fatter for it.

Run it with:

```bash
clojure -T:build uber
```

## Code formatting with zprint

Consistent formatting eliminates an entire class of review noise. We use [zprint](https://github.com/kkinnear/zprint) with a `.zprintrc` at the project root:

```clojure
{:width 100
 :style [:community :respect-bl :sort-require]
 :map {:comma? false :force-nl? true}
 :comment {:wrap? false}
 :vector {:respect-nl? true}
 :list {:hang? false :indent 2 :indent-arg 2}
 :pair {:force-nl? true}
 :fn-gt2-force-nl #{:fn}
 :fn-force-nl #{:noarg1-body :noarg1 :force-nl-body :force-nl :flow :flow-body :binding}
 :fn-map {"cond" [:pair-fn {:list {:respect-nl? true}}]
          "if" :arg1
          "def" :arg1-force-nl-body
          "defn" :arg1-force-nl-body
          "defn-" :arg1-force-nl-body
          "try" :flow-body
          "d/q" [:hang {:vector {:respect-nl? true}}]
          "->" [:noarg1-body {:list {:hang? true}}]
          "->>" [:noarg1-body {:list {:hang? true}}]
          "some->" [:noarg1-body {:list {:hang? true}}]
          "some->>" [:noarg1-body {:list {:hang? true}}]
          "cond->" [:arg1-pair-body {:list {:hang? true}}]
          "cond->>" [:arg1-pair-body {:list {:hang? true}}]
          "assoc" [:arg1-pair {:list {:hang? true}}]}}
```

The choices that set the file's character:

- **`:style [:community :respect-bl :sort-require]`** -- Start with community defaults, respect intentional blank lines (they carry meaning), and sort `require` clauses alphabetically.
- **`:map {:comma? false :force-nl? true}`** -- No commas in maps (this is Clojure, not JSON), and every key-value pair on its own line.
- **`:list {:hang? false :indent 2 :indent-arg 2}`** -- Disable hanging indentation globally. This is opinionated but it means function bodies always indent consistently at 2 spaces rather than aligning to the first argument.
- **The `:fn-map`** -- Custom formatting for specific forms. Threading macros (`->`, `->>`, etc.) and `assoc` get hanging enabled because they read better that way. `cond` gets pair formatting. Datomic queries (`d/q`) get special vector handling because query vectors have their own structure.

As the application grows, `:fn-map` grows with it: the repository's file adds rows for the inspector's `defview` macro, `db/transact*` from [the Datomic chapter](08-datomic.md), and the `log/*` macros. Same shape, more entries.

The `reformat` script applies zprint across the entire codebase:

```bash
#!/usr/bin/env bash
cd "$(dirname "$0")"
find src dev test \( -name '*.clj' -o -name '*.cljc' -o -name '*.edn' \) \
  | xargs -P4 -I{} zprint '{:search-config? true}' -w {}
```

The parentheses around the `-name` clauses are defensive. As the command stands they change nothing: with no action primary, `find` applies its implicit `-print` to the whole expression, parenthesized or not. They start to matter the moment someone appends an action. In `-name '*.clj' -o -name '*.edn' -print`, the implicit AND binds tighter than `-o`, so only the `.edn` files print; grouping the OR now means a later `-print` or `-exec` cannot silently change which files the script touches. (If you ever keep a large generated `.edn` in these trees -- seed data, a fixture dump -- add a `! -name 'that-file.edn'` clause to skip it, since zprint would otherwise churn on it for no benefit.) The `-P4` runs four parallel zprint processes. The `{:search-config? true}` tells each invocation to walk up the directory tree to find the `.zprintrc` file. The `-w` flag writes the formatted output back to the file in place.

(`zprint` is the one tool here that is not a Clojure dependency. The devcontainer from [the devcontainer chapter](03-devcontainer.md) installs its binary on the PATH; outside the container, grab the `zprint` release binary and put it on your PATH before running this script.)

Run it after every edit:

```bash
./reformat
```

## Static analysis with clj-kondo

clj-kondo catches bugs, style issues, and questionable patterns at lint time without running your code. Here is the `.clj-kondo/config.edn` as this chapter creates it -- the repository's copy opens with one more block, a `:discouraged-var` rule banning raw `datomic.api/pull` in favor of the wrappers [the Datomic chapter](08-datomic.md) builds, and grows `:lint-as` entries for macros later chapters define:

```clojure
{:linters
 {;; Documentation
  :missing-docstring {:level :warning}
  :docstring-no-summary {:level :warning}
  :docstring-leading-trailing-whitespace {:level :warning}

  ;; Correctness
  :shadowed-var {:level :warning}
  :condition-always-true {:level :warning}
  :equals-float {:level :warning}
  :used-underscored-binding {:level :warning}

  ;; Consistency
  :equals-expected-position {:level :warning
                             :position :first
                             :only-in-test-assertion true}
  :unsorted-required-namespaces {:level :warning}

  :warn-on-reflection {:level :warning
                       :warn-only-on-interop true}

  ;; Simplification
  :redundant-fn-wrapper {:level :warning}
  :redundant-call {:level :warning}
  :def-fn {:level :warning}
  :single-key-in {:level :warning}
  :unused-alias {:level :warning}
  :plus-one {:level :warning}
  :minus-one {:level :warning}}}
```

The linters fall into four categories.

**Documentation.** Every public function needs a docstring. When you are the only person on the project, future-you is the audience for these docstrings. They cost seconds to write and save minutes of re-reading code later.

**Correctness.** Shadowed variables are almost always a bug. Floating-point equality is always a bug. A condition that is always true is dead code at best and a logic error at worst.

**Consistency.** In test assertions, the expected value comes first: `(is (= expected actual))`. Require clauses are sorted alphabetically. These are small things that add up to a codebase that reads the same everywhere.

**Simplification.** Redundant function wrappers (`#(f %)` instead of just `f`), unnecessary `(get-in m [:k])` instead of `(get m :k)`, `(+ x 1)` instead of `(inc x)`. These are not bugs but they are noise, and clj-kondo catches them automatically.

The `:warn-on-reflection` linter closes the loop on the per-namespace `set!` idiom introduced alongside the compiler flags. It does not detect reflection; finding reflective call sites stays the compiler's job. What it checks is that the compiler's warning is switched on. A namespace missing `(set! *warn-on-reflection* true)` is flagged with `Var *warn-on-reflection* is not set in this namespace`; a namespace that has the line lints clean even over an unhinted call, which the compiler will then report at load time. The `:warn-only-on-interop true` setting scopes the demand to namespaces that actually contain interop calls, which is how the three pure-data namespaces (the schema and the i18n string tables) pass without carrying a line they could never trigger.

The `lint` script does two things: it runs clj-kondo, and it adds a grep-based guard for a rule clj-kondo cannot express. clj-kondo's exit code encodes what it found -- `0` for a clean tree, `2` when the worst finding was a warning, `3` for errors -- and since every linter above is enabled at `:warning` precisely because we intend to fix what it flags, the gate treats *any* finding as a failure: `rc >= 2` fails the script. The return code is captured rather than left to a bare invocation so the grep check below can share the same exit:

```bash
#!/usr/bin/env bash
# clj-kondo lint + companion grep checks for things clj-kondo can't see.
cd "$(dirname "$0")"

# clj-kondo returns: 0 = clean, 2 = warnings, 3 = errors.
# Any finding fails the gate: every linter we enable is one we intend to
# fix, so a warning here is a defect, not advice.
clj-kondo --lint src test
kondo_rc=$?

# Time-as-global check. clj-kondo's :discouraged-var only fires on Clojure
# vars, not Java static methods, so we grep for forbidden /now invocations.
# Everything must read time through myapp.time (see the clock chapter).
forbidden_pattern='(LocalDate/now|LocalDateTime/now|ZonedDateTime/now|OffsetDateTime/now|Instant/now|Year/now|YearMonth/now|System/currentTimeMillis|System\.currentTimeMillis)'
violations=$(grep -rn -E "$forbidden_pattern" src test \
  --include='*.clj' --include='*.cljs' --include='*.cljc' \
  | grep -v '^src/myapp/time\.clj:' \
  | grep -v '^test/myapp/time_test\.clj:' \
  || true)

time_rc=0
if [ -n "$violations" ]; then
  echo "Time-as-global violations (use myapp.time wrappers):"
  echo "$violations"
  time_rc=1
fi

# Fail on any clj-kondo finding (rc >= 2: warnings or errors) or any time violation.
if [ "$kondo_rc" -ge 2 ] || [ "$time_rc" -ne 0 ]; then
  exit 1
fi
```

Run it:

```bash
./lint
```

The script's second job -- the time-as-global grep -- enforces a rule that [the clock chapter](07-time-clock.md) introduces: every clock read must go through a `myapp.time` wrapper. It lives here because it lives in the same `lint` script. The mechanism is a plain grep precisely because direct calls like `(Instant/now)` are Java static methods that clj-kondo's `:discouraged-var` linter cannot see.

### A custom hook for `defn-`

clj-kondo's built-in `:missing-docstring` linter only checks `defn`, not `defn-` (private functions). If you want docstrings on private functions too -- and you should, because private does not mean self-explanatory -- you need a hook.

Create `.clj-kondo/hooks/missing_docstring.clj`:

```clojure
(ns hooks.missing-docstring
  (:require [clj-kondo.hooks-api :as api]))

(defn check-defn- [{:keys [node]}]
  (let [children (rest (:children node))
        name-node (first children)
        after-name (second children)]
    (when (and name-node after-name
               (not (api/string-node? after-name)))
      (api/reg-finding!
       (assoc (meta name-node)
              :message "Missing docstring."
              :type :missing-docstring)))))
```

Then register it in `config.edn`:

```clojure
:hooks
{:analyze-call {clojure.core/defn- hooks.missing-docstring/check-defn-}}
```

This walks the AST of every `defn-` form: if the token after the function name is not a string node (i.e., not a docstring), it registers a finding. Simple, effective.

## Putting it together

Your project now has three scripts and a build function:

| Command | What it does |
|---|---|
| `./reformat` | Format all Clojure files with zprint |
| `./lint` | Static analysis with clj-kondo |
| `clojure -T:build compile-strict` | AOT compile with warnings-as-errors |
| `clojure -T:build uber` | Build uberjar (includes compile-strict) |

These checks are fast (seconds, not minutes) and deterministic. Wire them into CI so they run on every push, and you have a codebase that stays clean without discipline -- the tools enforce it.

The discipline only works because it is there from the first commit: a gate added after three hundred warnings is a cleanup project, while the same gate added before the first is simply the floor you build on. With the floor in place, [the next chapter](05-web-server.md) puts an application on it: Ring, http-kit, and Reitit, composed into a running web server.
