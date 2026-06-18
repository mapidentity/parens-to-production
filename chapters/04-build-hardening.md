# Strict Compilation: Catching Reflection and Boxed Math from Day One


---

Most Clojure projects add performance checks late -- after mysterious slowdowns in production, after profiling reveals a hot path doing reflective method calls. By then the warnings number in the hundreds and fixing them is a grind.

There is a better way. If you wire up strict compilation from the very first commit, you catch reflection warnings and boxed math warnings the moment they appear. One warning at a time is easy to fix. Three hundred is a project.

This post walks through the build hardening setup we use: `tools.build` with fail-on-warnings, zprint for consistent formatting, and clj-kondo for static analysis. By the end you will have a build that refuses to produce an artifact with performance problems baked in, plus formatting and linting scripts that keep the codebase clean with minimal effort.

## The `:build` Alias

Everything starts in `deps.edn`. You only need one extra alias:

```clojure
:build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.7"}}
        :ns-default build}
```

That is it. The `:ns-default build` tells `clojure -T:build` to look for functions in a `build.clj` file at the project root. No build framework, no plugin ecosystem, just a Clojure file you can read and understand.

## The Build Namespace

Here is the build-hardening portion of `build.clj` -- the strict-compile gate and the uberjar task. The same file later grows a second half (content-hashing, SRI, the `assets`/`verify-assets` tasks, with two extra requires: `clojure.java.io` and `clojure.java.shell`) when [the asset pipeline chapter](19-asset-pipeline.md) arrives; we leave that out here and come back to it:

```clojure
(ns build
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b]))

(def lib
  'com.myapp/myapp)
(def version
  "0.1.0")
(def class-dir
  "target/classes")
(def uber-file
  "target/myapp.jar")
(def basis
  (delay (b/create-basis {:project "deps.edn"})))

(defn clean
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
  "AOT-compile src with *warn-on-reflection* and *unchecked-math* :warn-on-boxed,
  then fail if any warnings from our code were emitted. compile-clj runs in a
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

Let us break down the important parts.

### Delayed Basis

```clojure
(def basis
  (delay (b/create-basis {:project "deps.edn"})))
```

The basis (resolved dependency tree) is wrapped in a `delay` so it is only computed when actually needed. If you call `clojure -T:build clean`, there is no reason to resolve the entire classpath. Small thing, but it keeps the fast path fast.

### The Two Compiler Flags

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
;; => Reflection warning ... call to method length can't be resolved.

;; Hinted: `^String` tells the compiler `s` is a String, so `.length`
;; compiles to a direct, warning-free call.
(defn char-count [^String s] (.length s))
```

The hint goes on the argument (or on a `let` binding, or as a `^Type` tag in front of any expression). That one annotation is the entire fix the build is asking for.

**`*unchecked-math* :warn-on-boxed`** warns when a math operation forces boxing of primitive values. Boxing means wrapping a primitive `long` or `double` into a `Long` or `Double` object, which means heap allocation on what should be a register operation. In hot loops this is death by a thousand cuts.

### Why `compile-clj` Needs `:err :capture`

Here is a subtlety: `b/compile-clj` runs compilation in a subprocess. The warnings go to stderr of that subprocess. By default they scroll past and disappear. The `:err :capture` option collects them into a string so we can inspect them programmatically.

### `fail-on-warnings!` -- The Gate

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

This function scans the captured stderr line by line. It filters for lines that are both a warning (reflection or boxed math) *and* come from our own code (the `myapp/` namespace prefix). That second filter is important -- third-party libraries will emit their own warnings and you cannot fix those. You only fail on warnings you can actually act on.

The `(take 50)` is a safety valve. If you somehow accumulate a huge number of warnings (maybe you added a new dependency that triggered transitive compilation), you get the first 50 rather than a wall of text.

When any hits are found, it throws an `ex-info` with the warnings attached as data. The build fails. No jar is produced. You fix the type hints, run again, and move on.

### The Uberjar Pipeline

```clojure
(defn uber
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

The uberjar build is four steps: clean, copy sources and resources, compile strictly, then package. The strict compilation is not an optional lint step -- it is part of the build pipeline. You cannot produce a jar without passing the check. This is the key design decision. Making it part of the artifact build means it can never be skipped or forgotten.

Run it with:

```bash
clojure -T:build uber
```

## Code Formatting with zprint

Consistent formatting eliminates an entire class of review noise. We use [zprint](https://github.com/kkinnear/zprint) with a `.zprintrc` at the project root:

```clojure
{:width 100
 :style [:community :respect-bl :sort-require]
 :map {:comma? false :force-nl? true}
 :vector {:respect-nl? true}
 :list {:hang? false :indent 2 :indent-arg 2}
 :pair {:force-nl? true}
 :fn-gt2-force-nl #{:fn}
 :fn-force-nl #{:noarg1-body :noarg1 :force-nl-body :force-nl :flow :flow-body :binding}
 :fn-map {"cond" [:pair-fn {:list {:respect-nl? true}}]
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

A few choices worth calling out:

- **`:style [:community :respect-bl :sort-require]`** -- Start with community defaults, respect intentional blank lines (they carry meaning), and sort `require` clauses alphabetically.
- **`:map {:comma? false :force-nl? true}`** -- No commas in maps (this is Clojure, not JSON), and every key-value pair on its own line.
- **`:list {:hang? false :indent 2 :indent-arg 2}`** -- Disable hanging indentation globally. This is opinionated but it means function bodies always indent consistently at 2 spaces rather than aligning to the first argument.
- **The `:fn-map`** -- Custom formatting for specific forms. Threading macros (`->`, `->>`, etc.) and `assoc` get hanging enabled because they read better that way. `cond` gets pair formatting. Datomic queries (`d/q`) get special vector handling because query vectors have their own structure.

The `reformat` script applies zprint across the entire codebase:

```bash
#!/usr/bin/env bash
cd "$(dirname "$0")"
find src dev test \( -name '*.clj' -o -name '*.cljc' -o -name '*.edn' \) ! -name 'rgs-data.edn' \
  | xargs -P4 -I{} zprint '{:search-config? true}' -w {}
```

The parentheses around the `-name` clauses matter: without them, `find`'s `-o` (OR) binds more loosely than the implicit AND, and the predicate matches a different set of files than you intend. The `! -name 'rgs-data.edn'` excludes one large generated data file that zprint would otherwise churn on. The `-P4` runs four parallel zprint processes. The `{:search-config? true}` tells each invocation to walk up the directory tree to find the `.zprintrc` file. The `-w` flag writes the formatted output back to the file in place.

(`zprint` is the one tool here that is not a Clojure dependency. The devcontainer from [the devcontainer chapter](03-devcontainer.md) installs its binary on the PATH; outside the container, grab the `zprint` release binary and put it on your PATH before running this script.)

Run it after every edit:

```bash
./reformat
```

## Static Analysis with clj-kondo

clj-kondo catches bugs, style issues, and questionable patterns at lint time without running your code. Here is the `.clj-kondo/config.edn`:

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

The `:warn-on-reflection` linter with `:warn-only-on-interop true` is a nice complement to the compile-time check. It flags missing type hints during editing, before you even run the build. The `:warn-only-on-interop true` setting avoids false positives by only warning on actual Java interop calls, not every untyped binding.

The `lint` script does two things: it runs clj-kondo, and it adds a grep-based guard for a rule clj-kondo cannot express. clj-kondo exits `0` for clean, `1` for warnings, `2` for errors -- and we want warnings to be informational (not fail the build) while errors do fail, so the script captures the return code rather than letting a bare invocation decide:

```bash
#!/usr/bin/env bash
# clj-kondo lint + companion grep checks for things clj-kondo can't see.
cd "$(dirname "$0")"

# clj-kondo returns: 0 = clean, 1 = warnings, 2 = errors, 3 = bad invocation.
# Warnings are informational (don't fail); errors fail.
clj-kondo --lint src test
kondo_rc=$?

# Time-as-global check. clj-kondo's :discouraged-var only fires on Clojure
# vars, not Java static methods, so we grep for forbidden /now invocations.
# Everything must read time through myapp.time (see the Datomic chapter).
forbidden_pattern='(LocalDate/now|LocalDateTime/now|ZonedDateTime/now|OffsetDateTime/now|Instant/now|Year/now|YearMonth/now|System/currentTimeMillis)'
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

# Fail on any clj-kondo error (rc>=2) or any time violation.
if [ "$kondo_rc" -ge 2 ] || [ "$time_rc" -ne 0 ]; then
  exit 1
fi
```

Run it:

```bash
./lint
```

The time-as-global grep is the script's second job, and it is worth flagging now even though the rule it enforces only makes sense once [the Datomic chapter](07-datomic.md) introduces the `myapp.time` wrapper: direct clock reads like `(Instant/now)` are Java static-method calls, which clj-kondo's `:discouraged-var` linter cannot see, so a plain grep is the enforcement. If you are following along before that chapter exists, the `clj-kondo --lint` line alone is enough; add the grep block when you add `myapp.time`.

### A Custom Hook for `defn-`

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

## Putting It Together

Your project now has three scripts and a build function:

| Command | What it does |
|---|---|
| `./reformat` | Format all Clojure files with zprint |
| `./lint` | Static analysis with clj-kondo |
| `clojure -T:build compile-strict` | AOT compile with warnings-as-errors |
| `clojure -T:build uber` | Build uberjar (includes compile-strict) |

The workflow is:

1. Write code.
2. Run `./reformat` to fix formatting.
3. Run `./lint` to catch static issues.
4. Run `clojure -T:build uber` to produce an artifact, which will fail if any reflection or boxed math warnings exist in your code.

These checks are fast (seconds, not minutes) and deterministic. Wire them into CI so they run on every push, and you have a codebase that stays clean without discipline -- the tools enforce it.

## What You Have Now

After this setup, you have:

- **A `tools.build` configuration** that AOT-compiles your code with `*warn-on-reflection*` and `*unchecked-math* :warn-on-boxed` enabled, and fails the build if any warnings come from your namespaces.
- **A zprint configuration** that formats your Clojure code consistently, including custom rules for threading macros, Datomic queries, and map formatting.
- **A clj-kondo configuration** with linters for documentation, correctness, consistency, and code simplification, plus a custom hook for `defn-` docstrings.
- **Shell scripts** to run formatting and linting with a single command.

The investment is small -- one `build.clj` file, two config files, two shell scripts. The payoff compounds over the life of the project. Every reflection warning you catch now is one you never debug in production. Every formatting argument you never have (even with yourself) is time saved. Every lint warning is a potential bug caught before it ships.

Start strict. Stay strict. Your future self will thank you.
