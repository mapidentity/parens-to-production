(ns user
  "Development REPL helpers"
  (:require
    [datomic.api :as d]
    [myapp.core :as core]
    [myapp.db.core :as db]
    [hot-reload]
    [seed]))

(println "Loading development environment...")
(println "Available commands:")
(println "  (start!)    - Start the server + file watcher")
(println "  (stop!)     - Stop the server")
(println "  (restart!)  - Restart the server")
(println "  (reload!)   - Trigger browser reload")
(println "  (reset-db!) - Wipe and recreate the database")
(println "  (seed!)     - Add demo recipes")
(println "  (fresh!)    - reset-db! then seed!")

(defn start!
  "Start the dev server and file watcher"
  []
  (hot-reload/start))

(defn stop!
  "Stop the server"
  []
  (core/stop-server!))

(defn restart!
  "Restart the server"
  []
  (core/restart-server!))

(defn reload!
  "Trigger a browser reload via WebSocket"
  []
  (hot-reload/reload!))

(defn reset-db!
  "Reset the database (useful during development)"
  []
  (println "Resetting database...")
  (d/delete-database (db/db-uri))
  (db/create-database!)
  (println "Database reset complete"))

(defn seed!
  "Add demo recipes to the current database."
  []
  (seed/seed!))

(defn fresh!
  "Reset the database and load demo recipes."
  []
  (reset-db!)
  (seed!))

(comment
  ;; Inspect seeded data
  (require '[myapp.recipe.core :as recipe])
  (->> (recipe/all-recipes (db/get-db)) (map :recipe/title))
  ;; All users
  (d/q '[:find ?email :where [_ :user/email ?email]] (db/get-db)))
