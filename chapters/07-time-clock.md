# A Single Source of Time: A Swappable Clock

Most code reads the clock the way it breathes -- `Instant/now` here, `LocalDate/now` there, a `System/currentTimeMillis` wherever a timestamp is wanted. It works on the first day and quietly costs you on every day after, in two ways that are easy to miss until they bite. The first is that such code cannot be tested deterministically: a function that calls `Instant/now` internally produces a different answer every time it runs, so any assertion about *when* something happened is either racy or impossible. The second is that there is no single place to stand if you ever need to control time -- to freeze it, to ask "what did this look like last Tuesday," to make a token's expiry land exactly on a boundary in a test. Scatter the clock and you have given away both.

For this application the clock is not incidental. Two of its features lean on time directly: magic-link tokens that are valid for fifteen minutes and not a second longer ([the auth-tokens chapter](24-auth-tokens.md)), and the rate limiter that counts requests in a trailing window ([the email login-flow chapter](25-auth-email-flow.md)). A feature whose correctness *is* a statement about time needs a clock it can pin, not one it reads off the wall. One feature that sounds like it belongs on that list does not: the point-in-time history that is the whole point of a versioning site ("show me this recipe as it stood at that moment") runs on the database's own time axis, which [the recipe-domain chapter](09-recipe-domain.md) reads with `d/as-of`. The application clock never enters it.

Before the code, one distinction worth drawing sharply, because conflating its two halves is a common mess. There are two separate "time" problems in this app. One is the *type* of a timestamp -- `java.time.Instant` in our code versus the `java.util.Date` Datomic insists on storing -- which the next chapter handles with a conversion bridge. The other, the subject of this chapter, is the *source* of the current time: where "now" comes from. They are orthogonal, and we solve them in separate layers so neither leaks into the other.

## One namespace owns the clock

The principle is small: every read of the current time goes through one namespace, `myapp.time`, whose underlying `java.time.Clock` is swappable. Nothing else in the codebase calls `Instant/now` or its siblings directly.

```clojure
(ns myapp.time
  "Single source of truth for the current time. Everywhere else reads time
  through this namespace, never through Instant/now etc. directly."
  (:import [java.time Clock Instant LocalDate ZoneId]
           [java.util Date]))

(def ^ZoneId amsterdam (ZoneId/of "Europe/Amsterdam"))

(defonce ^:private clock-state (atom (Clock/system amsterdam)))
(defn current-clock ^Clock [] @clock-state)
(defn set-clock! [^Clock c] (reset! clock-state c))

(defmacro with-clock
  "Run body with c as the active clock, restoring the previous one after.
  Atom-based, so the rebind is visible on every thread in this JVM — including
  http-kit's request-handler threads and any thread Clojure's binding conveyance
  (future/agent/pmap) does not reach, which a thread-local binding would miss."
  [c & body]
  `(let [old# (current-clock)]
     (try (set-clock! ~c) ~@body (finally (set-clock! old#)))))

;; The ONLY allowed direct /now calls in the codebase live here.
(defn now      ^Instant [] (Instant/now (current-clock)))
(defn today    ^LocalDate [] (LocalDate/now (.withZone (current-clock) amsterdam)))
(defn now-date ^Date [] (Date/from (now)))            ; for raw Date interop

(defn fixed-clock
  "A clock frozen at `instant` — pin it in tests via (with-clock (fixed-clock i) …)."
  (^Clock [^Instant instant] (fixed-clock instant amsterdam))
  (^Clock [^Instant instant ^ZoneId zone] (Clock/fixed instant zone)))
```

The three accessors cover what the app actually needs: `now` returns an `Instant` (the modern, immutable, thread-safe timestamp every other layer works with), `today` a `LocalDate` in the application's display timezone, and `now-date` a `java.util.Date` for the rare interop path that genuinely needs one. The point is not the accessors, though; it is that all three read through `current-clock` rather than calling the JVM clock directly. In production that clock is `Clock/system`, indistinguishable from calling `Instant/now` yourself. The indirection costs nothing at runtime and buys everything at test time.

One more line of the listing is a product decision dressed as a constant: `amsterdam`, the single `ZoneId` that decides what `today` means. A calendar date exists only relative to a zone, and "today" is a question about the person asking, not the server answering. A product whose tenants sit across timezones has to resolve that zone per user (stored on the account, read from the session), at which point `today` stops being a nullary read and takes the user's zone as an argument. This application anchors display to one zone and works in absolute `Instant`s -- points on the timeline that carry no zone of their own (Datomic persists them as `java.util.Date`) -- so the decision stays confined to rendering: if a per-tenant zone ever becomes a requirement, it arrives as a parameter to `today` and the formatters, not as a data migration. The repository's copy of the namespace also carries a `today-in` accessor for the narrower cases where Amsterdam is simply the wrong zone; its docstring names UTC-anchored audit cutoffs.

Why a single swappable atom, rather than threading a clock argument through every function that reads the time, or reaching for `with-redefs` on `Instant/now` in each test? Threading a clock is the most explicit option, and the one a purist reaches for first. But it pushes a parameter through handlers, view helpers, and token code for a value that is genuinely ambient, paid on every signature whether or not a test is watching. `with-redefs` keeps the call sites clean, but it swaps Clojure vars, not Java statics: `Instant/now` is beyond its reach, so the tests would really be redeffing a wrapper var, which is to say the indirection has to exist anyway. And the rebinding it does is not thread-local but a mutation of the var's process-global root, so parallel tests stomp each other's clocks just as surely as they would fight over one atom.

That last critique invites the classic repair: make the wrapper's clock a `^:dynamic` var and rebind it with `binding`, which is thread-local, so parallel tests would each see their own clock and stomp nothing. What it buys in isolation it costs in reach. A dynamic binding is visible on the thread that established it, plus the points where Clojure itself conveys bindings (`future`, agent sends, `pmap`); it does not follow work onto threads Clojure did not start. In this application those threads are not hypothetical. Requests are served on http-kit's worker threads, and time gets read from more than the single thread a binding would cover: a `binding` conveys to `future`, agent sends, and `pmap`, but not onto a raw thread or an executor the app or a library hands work to. Pin a dynamic clock on one thread and code that reads the clock from another quietly gets the wall clock instead -- a test that looks pinned and is not. (Production's Datomic transactor is a separate process with its own clock, beyond the reach of any in-process rebind and nothing the app needs to pin: it supplies its own `:db/txInstant`, and the app's timestamps go into tx-data built on the request thread.) The atom's process-global rebind is visible from every thread in the JVM, which is the property this codebase actually needs; the price is that tests which pin the clock cannot run in parallel with each other. A richer library like `tick` would hand us a date-time type API we do not need, and its own mockable clock (`t/with-clock`) is the `^:dynamic`-var approach we just rejected. The atom is the deliberate trade: one piece of global mutable state, the honest cost and exactly the kind this book elsewhere refuses, bought back by a single auditable indirection point and the exact, race-free tests in the next section.

## Pinning time in a test

Because the clock lives behind a swappable atom, a test can freeze it for the duration of a body and every downstream `now`/`today` returns the pinned value -- no mocking framework, no dependency injection threaded through call sites:

```clojure
(deftest now-is-pinnable
  (let [t (Instant/parse "2025-06-15T12:00:00Z")]
    (time/with-clock (time/fixed-clock t)
      (is (= t (time/now))))))
```

That is the whole payoff, and the repository's suite collects it where the clock is genuinely the thing under test. The rate limiter's window test pins the clock at epoch zero, fills a one-hit budget, then re-pins it two seconds later and asserts the budget is back: exactly two seconds pass because the test says so, with no `Thread/sleep` and no tolerance band. The token-expiry test takes the complementary route. `sign-token` accepts its expiry instant as a parameter, so [the auth-tokens chapter](24-auth-tokens.md)'s test hands it a timestamp one second in the past and asserts that verification returns `nil`; only the one read no signature exposes, verification's own "now," goes through the wrapper. That is the division of labor in general: parameterize time where an API takes it as data, pin the clock for the reads that stay ambient, and either way the assertion about *when* is exact rather than racy. `with-clock` restores the previous clock in a `finally`, so a frozen clock never leaks from one test into the next.

## Making the rule enforceable

A convention that says "always go through `myapp.time`" is worth only as much as its weakest moment of discipline -- one stray `(Instant/now)` in a handler and a function silently becomes untestable again. So the rule is enforced, not merely documented. [The strict-compilation chapter](04-build-hardening.md)'s `lint` script greps the source for raw clock calls (`Instant/now`, `LocalDate/now`, `System/currentTimeMillis`, and the rest) and fails the build if one appears anywhere but this namespace and its own test file, which must call `Instant/now` to check that the wrapper's default tracks wallclock:

```bash
# Everything must read time through myapp.time (see this chapter).
forbidden_pattern='(LocalDate/now|LocalDateTime/now|ZonedDateTime/now|OffsetDateTime/now|Instant/now|Year/now|YearMonth/now|System/currentTimeMillis|System\.currentTimeMillis)'
```

It is a plain grep rather than a proper linter rule for the reason the strict-compilation chapter gave when it introduced the script: these calls are Java static methods, and clj-kondo's `:discouraged-var` only sees Clojure vars. The guard also has a boundary worth naming. The pattern lists the JDK's `now` family and `currentTimeMillis`; it does not catch every way to read a wall clock. The zero-argument `(Date.)` constructor reads it too, and because `now-date` keeps a `Date` interop path alive for Datomic, a stray `(Date.)` in a transaction helper is precisely the leak this grep would wave through. Extending the pattern is a one-line edit; the point is to know that the guard has an edge at all. From here on, application code calls `(time/now)`, and the build refuses every alternative the pattern can see.

With the source of time settled -- one origin, swappable, enforced -- the next question is what happens to those instants when they hit the database. Datomic stores time as `java.util.Date`, not `java.time.Instant`, and the next chapter is where that second time problem, the one of *type* rather than *source*, gets its own thin layer.
