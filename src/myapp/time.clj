(ns myapp.time
  "Single source of truth for the current time.

  Everywhere else in the codebase reads time through this namespace,
  never through `LocalDate/now`, `Instant/now`, etc. directly. The
  underlying `java.time.Clock` is swappable, which is what makes
  deterministic testing possible — pin the clock with `with-clock`
  and every downstream `now`/`today` call returns the pinned value.

  A clj-kondo `:discouraged-var` rule (`.clj-kondo/config.edn`) flags
  the JDK now-functions so the lint catches new bypasses. The wrappers
  below are the only place those calls are allowed.

  See CLAUDE.md § 'Time'."
  (:import
    [java.time Clock Instant LocalDate ZoneId ZonedDateTime]
    [java.util Date]))

(set! *warn-on-reflection* true)

(def ^ZoneId amsterdam
  "Europe/Amsterdam.

  The app's user-facing timezone. Recipe timestamps and what counts as
  'today' are anchored here. Use this rather than re-creating
  `(ZoneId/of \"Europe/Amsterdam\")` per namespace."
  (ZoneId/of "Europe/Amsterdam"))

(defonce ^:private clock-state (atom (Clock/system amsterdam)))

(defn current-clock
  "Return the active clock.

  Production starts with `Clock/system amsterdam`; tests rebind via
  `with-clock`."
  ^Clock []
  @clock-state)

(defn set-clock!
  "Replace the active clock.

  Intended for app startup; tests should prefer `with-clock` so the
  rebind is auto-restored."
  [^Clock c]
  (reset! clock-state c))

(defmacro with-clock
  "Run `body` with `c` as the active clock.

  Restores the previous value afterwards (even on exception). Atom-
  based, so the rebind survives thread boundaries — important for any
  work that crosses into the Datomic transactor. Tests should prefer
  this over direct `set-clock!` so cleanup is automatic."
  [c & body]
  `(let [old# (current-clock)]
     (try
       (set-clock! ~c)
       ~@body
       (finally (set-clock! old#)))))

;; The /now invocations below are the ONLY allowed direct calls in the
;; codebase. The `./lint` grep pass exempts this file by path. Every other
;; file must read time through the wrappers in this namespace.

(defn now
  "Return the current `Instant` per the active clock."
  ^Instant []
  (Instant/now (current-clock)))

(defn today
  "Return today's `LocalDate` in Europe/Amsterdam per the active clock.

  This is the right default for date-stamping user actions in the app's
  display timezone. Reach for `today-in` only when you genuinely need a
  different zone."
  ^LocalDate []
  (LocalDate/now (.withZone (current-clock) amsterdam)))

(defn today-in
  "Return today's `LocalDate` in `zone` per the active clock.

  Use only when Amsterdam isn't the right answer — e.g. UTC-anchored
  audit cutoffs."
  ^LocalDate [^ZoneId zone]
  (LocalDate/now (.withZone (current-clock) zone)))

(defn now-amsterdam
  "Return the current `ZonedDateTime` in Europe/Amsterdam per the active clock."
  ^ZonedDateTime []
  (ZonedDateTime/now (.withZone (current-clock) amsterdam)))

(defn now-date
  "Return the current time as `java.util.Date`.

  Convenience for legacy interop paths that need `Date` directly
  (e.g. raw Datomic transactions bypassing `transact*`'s Instant→Date
  conversion). Prefer `now` everywhere else."
  ^Date []
  (Date/from (now)))

(defn fixed-clock
  "Return a clock that always returns `instant`, in `amsterdam` (or `zone`).

  Use in tests via `(with-clock (time/fixed-clock i) ...)`."
  (^Clock [^Instant instant] (fixed-clock instant amsterdam))
  (^Clock [^Instant instant ^ZoneId zone] (Clock/fixed instant zone)))

(defn fixed-clock-at
  "Return a fixed clock pinned to midnight on `local-date` in `amsterdam` (or `zone`).

  Convenience for date-driven tests where the wallclock instant
  doesn't matter, only the day does."
  (^Clock [^LocalDate local-date] (fixed-clock-at local-date amsterdam))
  (^Clock [^LocalDate local-date ^ZoneId zone]
   (Clock/fixed
     (-> local-date
         (.atStartOfDay zone)
         .toInstant)
     zone)))

(comment
  ;; REPL playground
  (now)
  (today)
  (now-amsterdam)
  (with-clock (fixed-clock-at (LocalDate/of 2030 1 1)) [(now) (today)]))
