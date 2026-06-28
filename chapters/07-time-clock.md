# A Single Source of Time: A Swappable Clock

Most code reads the clock the way it breathes -- `Instant/now` here, `LocalDate/now` there, a `System/currentTimeMillis` wherever a timestamp is wanted. It works on the first day and quietly costs you on every day after, in two ways that are easy to miss until they bite. The first is that such code cannot be tested deterministically: a function that calls `Instant/now` internally produces a different answer every time it runs, so any assertion about *when* something happened is either racy or impossible. The second is that there is no single place to stand if you ever need to control time -- to freeze it, to ask "what did this look like last Tuesday," to make a token's expiry land exactly on a boundary in a test. Scatter the clock and you have given away both.

For this application the clock is not incidental. Two of its features lean on time directly: magic-link tokens that are valid for fifteen minutes and not a second longer ([the auth-tokens chapter](19-auth-tokens.md)), and the point-in-time history that is the whole point of a versioning site -- "show me this recipe as it stood at that moment." A feature whose correctness *is* a statement about time needs a clock it can pin, not one it reads off the wall.

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
  "Run body with c as the active clock, restoring the previous one after."
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

Why a single swappable atom, rather than threading a clock argument through every function that reads the time, or reaching for `with-redefs` on `Instant/now` in each test? Threading a clock is the most explicit option, and the one a purist reaches for first -- but it pushes a parameter through handlers, view helpers, and token code for a value that is genuinely ambient, paid on every signature whether or not a test is watching. `with-redefs` keeps the call sites clean but rebinds a Java static per test, which is brittle and thread-local in ways that bite under parallel runs. A richer library like `tick` would hand us types we do not need here. The atom is the deliberate trade: one piece of global mutable state -- the honest cost, and exactly the kind this book elsewhere refuses -- bought back by a single auditable indirection point and the exact, race-free tests in the next section. (`java.time` is the type layer; the swappable `Clock` is the only machinery we add on top of it.)

## Pinning time in a test

Because the clock lives behind a swappable atom, a test can freeze it for the duration of a body and every downstream `now`/`today` returns the pinned value -- no mocking framework, no dependency injection threaded through call sites:

```clojure
(deftest now-is-pinnable
  (let [t (Instant/parse "2025-06-15T12:00:00Z")]
    (time/with-clock (time/fixed-clock t)
      (is (= t (time/now))))))
```

That is the whole payoff. A token-expiry test can advance the clock past the fifteen-minute window and assert the token is dead; a point-in-time assertion can fix "now" and check exactly what a past state rendered. Both are exact rather than racy, and both are impossible if the code under test reaches for `Instant/now` on its own. `with-clock` restores the previous clock in a `finally`, so a frozen clock never leaks from one test into the next.

## Making the rule enforceable

A convention that says "always go through `myapp.time`" is worth only as much as its weakest moment of discipline -- one stray `(Instant/now)` in a handler and a function silently becomes untestable again. So the rule is enforced, not merely documented. [The strict-compilation chapter](04-build-hardening.md)'s `lint` script greps the source for every raw clock call -- `Instant/now`, `LocalDate/now`, `System/currentTimeMillis`, and the rest -- and fails the build if one appears anywhere but this file:

```bash
# Everything must read time through myapp.time (see this chapter).
forbidden_pattern='(LocalDate/now|LocalDateTime/now|ZonedDateTime/now|OffsetDateTime/now|Instant/now|Year/now|YearMonth/now|System/currentTimeMillis)'
```

It is a plain grep rather than a proper linter rule for a precise reason: these calls are Java static methods, and clj-kondo's `:discouraged-var` mechanism only sees Clojure vars, not interop. A grep sees the text, which is exactly the level the rule lives at. From here on, application code calls `(time/now)`; the build refuses to let it do otherwise.

With the source of time settled -- one origin, swappable, enforced -- the next question is what happens to those instants when they hit the database. Datomic stores time as `java.util.Date`, not `java.time.Instant`, and the next chapter is where that second time problem, the one of *type* rather than *source*, gets its own thin layer.
